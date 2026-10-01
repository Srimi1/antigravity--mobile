package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.runtime.ApprovalCategory
import dev.srimi.antigravitymobile.runtime.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject

enum class CliAnswer { Approved, Declined, Cancelled, Interrupted }
sealed interface CliEvent {
    data class Session(val id: String) : CliEvent
    data class Text(val delta: String) : CliEvent
    data class Approval(val requestId: String, val itemId: String, val category: ApprovalCategory,
        val summary: String, val detail: String, val supported: Boolean) : CliEvent
    data class ToolResult(val itemId: String, val tool: String, val outcome: ToolOutcome) : CliEvent
    data class Finished(val outcome: ToolOutcome, val response: String = "") : CliEvent
}
data class CliBatch(val events: List<CliEvent> = emptyList(), val writes: List<JSONObject> = emptyList())

internal fun JSONObject.requiredText(key: String, max: Int = 64_000): String =
    BridgeSecurity.string(this, key).also { if (it.length > max) throw BridgeProtocolException() }
internal fun JSONObject.identifier(key: String) = requiredText(key, 128).also {
    if (!Regex("[A-Za-z0-9_.:-]{1,128}").matches(it)) throw BridgeProtocolException()
}
internal fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw BridgeProtocolException()
}
internal fun JSONObject.nullableObject(key: String): JSONObject? =
    if (!has(key) || isNull(key)) null else get(key) as? JSONObject ?: throw BridgeProtocolException()
internal fun JSONObject.nullableText(key: String, max: Int = 128): String? =
    if (!has(key) || isNull(key)) null else requiredText(key, max)
internal fun rpcId(value: Any): String = when (value) {
    is String -> "s:${value.also { if (it.length !in 1..128 || it.any(Char::isISOControl)) throw BridgeProtocolException() }}"
    is Int, is Long -> "n:$value"
    else -> throw BridgeProtocolException()
}

/** Stable app-server subset only: no account, process/spawn, command/exec or shellCommand proxy. */
class CodexProtocol(private val workspace: String, private val prompt: String, private val resumeThread: String? = null) {
    private enum class Phase { New, Initializing, Thread, Turn, Running, Done }
    private var phase = Phase.New
    private var thread: String? = null
    private var announcedThread: String? = null
    private var turn: String? = null
    private var stopping = false
    private val pending = mutableMapOf<String, Pair<Any, CliEvent.Approval>>()
    private val seenRequests = mutableSetOf<String>()
    private val decisions = mutableMapOf<String, CliAnswer>()
    private val text = linkedMapOf<String, String>()
    private var terminal: CliEvent.Finished? = null
    init { require(workspace.startsWith('/') && !workspace.contains('\u0000')); require(prompt.length <= 100_000) }
    private fun rpc(method: String, id: String, params: JSONObject) = JSONObject().put("id", id).put("method", method).put("params", params)
    fun begin(): JSONObject {
        check(phase == Phase.New)
        phase = Phase.Initializing
        return rpc("initialize", "agm-init", JSONObject().put("clientInfo", JSONObject()
            .put("name", "antigravity_mobile").put("title", "Antigravity Mobile").put("version", "0.5.2"))
            .put("capabilities", JSONObject().put("experimentalApi", false)))
    }
    fun receive(message: JSONObject): CliBatch = BridgeSecurity.guard {
        if (phase == Phase.Done) return@guard CliBatch()
        if (message.toString().toByteArray().size > BridgeSecurity.MAX_BODY_BYTES) throw BridgeProtocolException()
        if (!message.has("method")) return@guard response(message)
        val method = message.requiredText("method", 128)
        val params = message.optJSONObject("params") ?: throw BridgeProtocolException()
        if (method == "thread/started") {
            if (phase !in setOf(Phase.Thread, Phase.Turn, Phase.Running)) throw BridgeProtocolException()
            val announced = params.getJSONObject("thread").identifier("id")
            if ((thread != null && thread != announced) || (announcedThread != null && announcedThread != announced)) throw BridgeProtocolException()
            announcedThread = announced
            return@guard CliBatch()
        }
        if (method == "error") return@guard finish(ToolOutcome.Failed("Codex reported a turn error"))
        if (method.startsWith("account/")) throw BridgeProtocolException()
        if (params.has("threadId") && params.identifier("threadId") != thread) throw BridgeProtocolException()
        if (params.has("turnId") && params.identifier("turnId") != turn) throw BridgeProtocolException()
        if (method == "turn/started") {
            if (phase != Phase.Turn && phase != Phase.Running) throw BridgeProtocolException()
            bindTurn(params.getJSONObject("turn")); return@guard CliBatch()
        }
        if (method.startsWith("item/") || method == "turn/completed") {
            if (phase != Phase.Running || params.identifier("threadId") != thread) throw BridgeProtocolException()
            if (method.startsWith("item/") && params.identifier("turnId") != turn) throw BridgeProtocolException()
        }
        when (method) {
            "item/agentMessage/delta" -> {
                val item = params.identifier("itemId"); val delta = params.requiredText("delta", 16_000)
                val combined = text[item].orEmpty() + delta
                if (text.values.sumOf { it.length } - text[item].orEmpty().length + combined.length > 64_000) throw BridgeProtocolException()
                text[item] = combined; CliBatch(listOf(CliEvent.Text(delta)))
            }
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                val wire = message.get("id"); val id = rpcId(wire)
                if (stopping) return@guard CliBatch(writes = listOf(JSONObject().put("id", wire)
                    .put("result", JSONObject().put("decision", "cancel"))))
                if (!seenRequests.add(id) || seenRequests.size > 1000 || pending.size >= 32) throw BridgeProtocolException()
                val item = params.identifier("itemId")
                val edit = method == "item/fileChange/requestApproval"
                val supported = if (edit) !params.has("grantRoot") || params.isNull("grantRoot") || params.optString("grantRoot") == workspace
                    else params.optString("cwd") == workspace && params.optJSONObject("networkApprovalContext") == null &&
                        params.optJSONObject("additionalPermissions") == null && (!params.has("proposedExecpolicyAmendment") || params.isNull("proposedExecpolicyAmendment"))
                val request = CliEvent.Approval(id, item, if (edit) ApprovalCategory.Edit else ApprovalCategory.Command,
                    if (edit) "Codex file change in private workspace" else "Codex command in private workspace",
                    if (edit) params.optString("reason", "Review the CLI's proposed file change before continuing")
                    else params.optString("command", "Command preview unavailable"), supported)
                if (request.detail.length > 64_000) throw BridgeProtocolException()
                pending[id] = wire to request
                CliBatch(listOf(request))
            }
            "item/completed" -> {
                val item = params.getJSONObject("item"); val id = item.identifier("id")
                when (item.requiredText("type", 64)) {
                    "agentMessage" -> {
                        val final = item.requiredText("text")
                        if (text.values.sumOf { it.length } - text[id].orEmpty().length + final.length > 64_000) throw BridgeProtocolException()
                        text[id] = final; CliBatch()
                    }
                    "commandExecution", "fileChange", "mcpToolCall" -> {
                        val tool = item.requiredText("type", 64)
                        val output = item.optString("aggregatedOutput", "").take(60_000)
                        val exitCode = if (item.has("exitCode") && !item.isNull("exitCode")) item.integer("exitCode") else null
                        val outcome = when (item.requiredText("status", 32)) {
                            "completed" -> if (tool == "commandExecution" && exitCode != null && exitCode != 0L)
                                ToolOutcome.Failed("CLI command exited $exitCode: $output") else ToolOutcome.Success(output.ifBlank { "CLI reported completion in its private workspace" })
                            "failed" -> ToolOutcome.Failed("CLI $tool failed${if (output.isBlank()) "" else ": $output"}")
                            "declined" -> if (decisions[id] == CliAnswer.Declined) ToolOutcome.Cancelled else
                                ToolOutcome.RuntimeUnavailable("CLI permission unavailable; review this action in Termux")
                            else -> throw BridgeProtocolException()
                        }
                        CliBatch(listOf(CliEvent.ToolResult(id, tool, outcome)))
                    }
                    else -> CliBatch()
                }
            }
            "turn/completed" -> {
                val result = params.getJSONObject("turn")
                if (result.identifier("id") != turn) throw BridgeProtocolException()
                when (result.requiredText("status", 32)) {
                    "completed" -> finish(ToolOutcome.Success("Codex turn completed"), text.values.joinToString("\n"))
                    "failed" -> finish(ToolOutcome.Failed("Codex turn failed"), text.values.joinToString("\n"))
                    "interrupted" -> finish(if (stopping) ToolOutcome.Cancelled else ToolOutcome.Interrupted, text.values.joinToString("\n"))
                    else -> throw BridgeProtocolException()
                }
            }
            "item/permissions/requestApproval", "mcpServer/elicitation/request", "item/tool/requestUserInput" -> {
                val wire = message.get("id"); val id = rpcId(wire)
                if (!seenRequests.add(id) || seenRequests.size > 1000) throw BridgeProtocolException()
                val (reply, outcome) = when (method) {
                    // Extra filesystem/network access is never granted from the phone; the turn continues without it.
                    "item/permissions/requestApproval" -> JSONObject().put("permissions", JSONObject()) to
                        ToolOutcome.RuntimeUnavailable("Codex asked for extra permissions; none were granted. Review in Termux if needed")
                    "mcpServer/elicitation/request" -> if (!stopping && params.optString("serverName") == "agm_native")
                        JSONObject().put("action", "accept").put("content", JSONObject()) to null
                        else JSONObject().put("action", if (stopping) "cancel" else "decline") to
                            ToolOutcome.RuntimeUnavailable("MCP server ${params.optString("serverName").take(64)} asked for input; answer it in Termux")
                    else -> JSONObject().put("answers", JSONObject()) to
                        ToolOutcome.RuntimeUnavailable("Codex asked a question the phone cannot answer headlessly; no answer was given")
                }
                val item = params.optString("itemId").takeIf { Regex("[A-Za-z0-9_.:-]{1,128}").matches(it) } ?: "request:$id".take(128)
                CliBatch(listOfNotNull(outcome?.let { CliEvent.ToolResult(item, method.substringAfter('/').substringBefore('/'), it) }),
                    listOf(JSONObject().put("id", wire).put("result", reply)))
            }
            else -> {
                // Informational notifications are ignored; an unsupported server request gets an error, never silence.
                if (!message.has("id")) return@guard CliBatch()
                val wire = message.get("id"); val id = rpcId(wire)
                if (!seenRequests.add(id) || seenRequests.size > 1000) throw BridgeProtocolException()
                CliBatch(listOf(CliEvent.ToolResult("request:$id".take(128), method.take(64),
                    ToolOutcome.RuntimeUnavailable("Codex request $method is not supported by Antigravity Mobile"))),
                    listOf(JSONObject().put("id", wire).put("error", JSONObject().put("code", -32601).put("message", "Not supported by Antigravity Mobile"))))
            }
        }
    }
    private fun response(message: JSONObject): CliBatch {
        val id = message.requiredText("id", 128)
        if (stopping && id != "agm-stop") return CliBatch()
        if (message.has("error")) return finish(ToolOutcome.RuntimeUnavailable("Codex request failed; check the CLI in Termux"))
        val result = message.getJSONObject("result")
        return when (id) {
            "agm-init" -> {
                if (phase != Phase.Initializing) throw BridgeProtocolException()
                phase = Phase.Thread
                val params = JSONObject().put("cwd", workspace).put("sandbox", "workspace-write")
                    .put("approvalPolicy", "on-request").put("approvalsReviewer", "user")
                if (resumeThread != null) params.put("threadId", resumeThread)
                CliBatch(writes = listOf(JSONObject().put("method", "initialized"), rpc(if (resumeThread == null) "thread/start" else "thread/resume", "agm-thread", params)))
            }
            "agm-thread" -> {
                if (phase != Phase.Thread) throw BridgeProtocolException()
                val idValue = result.getJSONObject("thread").identifier("id")
                if (resumeThread != null && resumeThread != idValue) throw BridgeProtocolException()
                if (announcedThread != null && announcedThread != idValue) throw BridgeProtocolException()
                val sandbox = result.getJSONObject("sandbox")
                if (result.requiredText("cwd") != workspace || sandbox.optString("type") != "workspaceWrite" ||
                    sandbox.optBoolean("networkAccess", false) || result.optString("approvalPolicy") != "on-request" ||
                    result.optString("approvalsReviewer") != "user") return finish(ToolOutcome.RuntimeUnavailable("Codex did not confirm the required workspace sandbox and user approvals"))
                thread = idValue; phase = Phase.Turn
                val policy = JSONObject().put("type", "workspaceWrite").put("writableRoots", JSONArray().put(workspace))
                    .put("networkAccess", false).put("excludeSlashTmp", true).put("excludeTmpdirEnvVar", true)
                val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt).put("text_elements", JSONArray()))
                CliBatch(listOf(CliEvent.Session(idValue)), listOf(rpc("turn/start", "agm-turn", JSONObject().put("threadId", idValue)
                    .put("input", input).put("cwd", workspace).put("approvalPolicy", "on-request").put("approvalsReviewer", "user").put("sandboxPolicy", policy))))
            }
            "agm-turn" -> { if (phase != Phase.Turn && phase != Phase.Running) throw BridgeProtocolException(); bindTurn(result.getJSONObject("turn")); CliBatch() }
            "agm-stop" -> { if (!stopping) throw BridgeProtocolException(); CliBatch() }
            else -> throw BridgeProtocolException()
        }
    }
    private fun bindTurn(value: JSONObject) {
        val id = value.identifier("id")
        if (turn != null && turn != id) throw BridgeProtocolException()
        turn = id; phase = Phase.Running
    }
    fun answer(requestId: String, decision: CliAnswer): JSONObject? {
        if (stopping || phase == Phase.Done) return null
        val (wire, request) = pending.remove(requestId) ?: return null
        val effective = if (request.supported) decision else CliAnswer.Cancelled
        decisions[request.itemId] = effective
        val value = when (effective) { CliAnswer.Approved -> "accept"; CliAnswer.Declined -> "decline"; CliAnswer.Cancelled, CliAnswer.Interrupted -> "cancel" }
        return JSONObject().put("id", wire).put("result", JSONObject().put("decision", value))
    }
    fun interrupt(): JSONObject? {
        stopping = true
        pending.clear()
        if (thread == null || turn == null || phase == Phase.Done) return null
        return rpc("turn/interrupt", "agm-stop", JSONObject().put("threadId", thread).put("turnId", turn))
    }
    fun exited(code: Int): CliEvent.Finished {
        phase = Phase.Done; pending.clear()
        return terminal ?: CliEvent.Finished(if (stopping) ToolOutcome.Cancelled else if (code == 0)
            ToolOutcome.Interrupted else ToolOutcome.RuntimeUnavailable("Codex exited before a recorded terminal turn; check the CLI in Termux"))
            .also { terminal = it }
    }
    private fun finish(outcome: ToolOutcome, response: String = ""): CliBatch {
        phase = Phase.Done; pending.clear()
        val result = CliEvent.Finished(outcome, response.take(64_000)); terminal = result
        return CliBatch(listOf(result))
    }
}

/** Headless streaming has no interactive approval channel. Permission denial is a runtime limitation. */
class AntigravityProtocol(private val workspace: String, private val resumeConversation: String? = null) {
    private var conversation: String? = null
    private var awaiting = false
    private var stopping = false
    private var denied = false
    private var completed = false
    private var turns: Long? = null
    private var terminal: CliEvent.Finished? = null
    fun user(prompt: String): JSONObject {
        check(!awaiting); require(prompt.isNotBlank() && prompt.length <= 100_000 && !prompt.trimStart().startsWith('/'))
        awaiting = true; denied = false; completed = false; terminal = null
        return JSONObject().put("event", "user").put("message", JSONObject().put("content", prompt))
    }
    fun receive(message: JSONObject): CliBatch = BridgeSecurity.guard {
        if (message.toString().toByteArray().size > BridgeSecurity.MAX_BODY_BYTES) throw BridgeProtocolException()
        when (message.requiredText("event", 64)) {
            "init" -> {
                if (conversation != null) throw BridgeProtocolException()
                val id = message.identifier("conversation_id")
                if (resumeConversation != null && resumeConversation != id) throw BridgeProtocolException()
                val init = message.getJSONObject("init")
                if (init.optString("cwd") != workspace || init.optString("permission_mode") != "request-review") {
                    val blocked = CliEvent.Finished(ToolOutcome.RuntimeUnavailable("Antigravity CLI did not confirm private workspace and review permissions"))
                    terminal = blocked
                    return@guard CliBatch(listOf(blocked))
                }
                conversation = id; CliBatch(listOf(CliEvent.Session(id)))
            }
            "step_update" -> {
                val step = message.getJSONObject("step_update"); scope(step)
                if (step.optString("state") !in setOf("ACTIVE", "DONE") || step.integer("step_index") < 0) throw BridgeProtocolException()
                when (step.requiredText("step_type", 64)) {
                    "agent_response" -> CliBatch(listOf(CliEvent.Text(step.optString("text_delta", "").also { if (it.length > 16_000) throw BridgeProtocolException() })))
                    "tool" -> {
                        if (step.optString("state") != "DONE") return@guard CliBatch()
                        val tool = step.getJSONObject("tool_info"); val name = tool.requiredText("name", 128)
                        val error = tool.nullableObject("error")
                        val permission = error?.optString("type", "")?.uppercase()?.contains("PERMISSION") == true ||
                            error?.optString("message", "")?.let(::permissionNotice) == true
                        if (permission) denied = true
                        val outcome = when {
                            permission -> ToolOutcome.RuntimeUnavailable("Headless CLI cannot obtain this permission. Open Termux to review the action; no user decline recorded")
                            error != null -> ToolOutcome.Failed("Antigravity CLI tool $name failed")
                            else -> ToolOutcome.Success(tool.optString("output", "CLI tool completed in private workspace").take(60_000))
                        }
                        CliBatch(listOf(CliEvent.ToolResult("step:${step.integer("step_index")}", name, outcome)))
                    }
                    else -> CliBatch()
                }
            }
            "result" -> {
                val result = message.getJSONObject("result"); scope(result)
                val count = result.integer("num_turns")
                if (count < 0 || turns?.let { count <= it } == true) throw BridgeProtocolException()
                val outcome = when (result.requiredText("status", 32)) {
                    "SUCCESS" -> if (denied) ToolOutcome.RuntimeUnavailable("CLI permission unavailable; review required actions in Termux") else ToolOutcome.Success("Antigravity CLI turn completed")
                    "ERROR", "INVALID" -> ToolOutcome.Failed("Antigravity CLI turn failed; check the CLI in Termux")
                    "CANCELED" -> if (stopping) ToolOutcome.Cancelled else ToolOutcome.Interrupted
                    "INTERRUPTED" -> if (stopping) ToolOutcome.Cancelled else ToolOutcome.Interrupted
                    "WAITING", "RUNNING" -> ToolOutcome.Interrupted
                    else -> throw BridgeProtocolException()
                }
                val response = result.requiredText("response")
                turns = count; awaiting = false; completed = true
                val finished = CliEvent.Finished(outcome, response); terminal = finished
                CliBatch(listOf(finished))
            }
            else -> throw BridgeProtocolException()
        }
    }
    private fun scope(value: JSONObject) {
        if (!awaiting || conversation == null || value.identifier("conversation_id") != conversation) throw BridgeProtocolException()
    }
    fun stderr(line: String) { if (permissionNotice(line)) denied = true }
    private fun permissionNotice(line: String): Boolean {
        val text = line.lowercase()
        return "soft-denied" in text || (("permission" in text || "approval" in text) && ("denied" in text || "cannot" in text || "unavailable" in text))
    }
    fun cancel() { stopping = true }
    fun exited(code: Int): CliEvent.Finished = if (denied && terminal?.outcome is ToolOutcome.Success)
        CliEvent.Finished(ToolOutcome.RuntimeUnavailable("Headless CLI permission unavailable; open Termux to review the action"), terminal!!.response)
        else terminal ?: CliEvent.Finished(when {
        stopping -> ToolOutcome.Cancelled
        denied -> ToolOutcome.RuntimeUnavailable("Headless CLI permission unavailable; open Termux to review the action")
        !completed -> if (code == 0) ToolOutcome.Interrupted else ToolOutcome.RuntimeUnavailable("Antigravity CLI exited before a recorded result; check Termux")
        else -> ToolOutcome.Success("CLI exited after a recorded result")
    })
}
