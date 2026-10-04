package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ToolPreview(val summary: String, val detail: String = "", val buildId: String? = null)

/** Tools offered to a model. Approval categories must describe the actual side effect. */
interface ToolHost {
    val specs: List<ToolSpec>
    fun requiresApproval(name: String): Boolean
    fun approvalCategory(name: String): ApprovalCategory? = if (requiresApproval(name)) ApprovalCategory.Edit else null
    suspend fun describe(call: AgentItem.ToolCall): ToolPreview
    suspend fun execute(call: AgentItem.ToolCall): ToolOutcome
    suspend fun resolve(call: AgentItem.ToolCall, decision: ApprovalDecision) {}
}

fun interface ApprovalGate {
    suspend fun decide(key: ApprovalKey, call: AgentItem.ToolCall, preview: ToolPreview): ApprovalDecision
}

interface AgentListener {
    suspend fun onTextDelta(delta: String) {}
    suspend fun onAssistantMessage(text: String) {}
    suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, result: RecordedExecution) {}
    suspend fun onNotice(text: String) {}
}

/** Complete turns and action results are checkpointed before the next operation. Partial streams never run tools. */
class AgentOrchestrator(
    private val model: AgentModel,
    private val tools: ToolHost,
    private val approvals: ApprovalGate,
    private val listener: AgentListener,
    private val maxSteps: Int = 30,
    private val journal: AgentJournal = TransientAgentJournal(),
) {
    suspend fun run(instructions: String, history: List<AgentItem>, prompt: String): List<AgentItem> {
        val items = history + AgentItem.User(prompt)
        journal.checkpoint(items, 0, LoopNext.Model)
        return resume(instructions, items)
    }

    suspend fun resume(instructions: String, history: List<AgentItem>, completedSteps: Int = 0,
        nextStep: LoopNext = LoopNext.Model): List<AgentItem> {
        val items = history.toMutableList()
        var steps = completedSteps
        var next = nextStep
        while (next != LoopNext.Done) {
            currentCoroutineContext().ensureActive()
            if (next == LoopNext.Tools) {
                val resolved = items.filterIsInstance<AgentItem.ToolResult>().map { it.callId }.toSet()
                val calls = items.filterIsInstance<AgentItem.ToolCall>().filter { it.callId !in resolved }
                for ((index, call) in calls.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val result = runTool(call)
                    items += AgentItem.ToolResult(call.callId, result.output)
                    next = if (index == calls.lastIndex) LoopNext.Model else LoopNext.Tools
                    journal.checkpoint(items, steps, next)
                }
                if (calls.isEmpty()) next = LoopNext.Model
            }
            if (steps >= maxSteps) {
                listener.onNotice("Stopped after $maxSteps model steps. Send another message to continue.")
                journal.checkpoint(items, steps, LoopNext.Done)
                break
            }
            val produced = mutableListOf<AgentItem>()
            var completed = false
            try {
                model.streamAgentTurn(AgentRequest(instructions, items.toList(), tools.specs)).collect { event ->
                    check(!completed) { "Provider sent events after completion" }
                    when (event) {
                        is ProviderEvent.Text -> listener.onTextDelta(event.delta)
                        is ProviderEvent.Item -> {
                            produced += event.item
                            require(produced.size <= 1000) { "Provider turn has too many items" }
                        }
                        ProviderEvent.Completed -> completed = true
                    }
                }
                check(completed) { "The provider stream ended before the turn completed" }
                val callIds = produced.filterIsInstance<AgentItem.ToolCall>().map { it.callId }
                require(callIds.all { it.isNotBlank() } && callIds.distinct().size == callIds.size) { "Provider reused a tool call identity" }
                require(items.filterIsInstance<AgentItem.ToolCall>().none { it.callId in callIds }) { "Provider reused a completed tool call identity" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { throw ModelRequestFailure(error) }
            steps++
            next = if (produced.any { it is AgentItem.ToolCall }) LoopNext.Tools else LoopNext.Done
            items += produced
            journal.checkpoint(items, steps, next)
            produced.filterIsInstance<AgentItem.Assistant>().filter { it.text.isNotBlank() }.forEach { listener.onAssistantMessage(it.text) }
        }
        return items
    }

    private suspend fun runTool(call: AgentItem.ToolCall): RecordedExecution {
        journal.restore(call)?.let { return it }
        var key = journal.begin(call)
        var preview = ToolPreview("${call.name} (invalid request)")
        var claimed = false
        suspend fun finish(result: RecordedExecution): RecordedExecution {
            journal.finish(key, result)
            listener.onToolFinished(call, preview, result)
            return result
        }
        try {
            preview = tools.describe(call)
            val category = tools.approvalCategory(call.name)
            key = journal.prepared(key, preview, category)
            if (category != null) {
                val decision = approvals.decide(key, call, preview)
                require(decision.key == key) { "Approval belongs to another action" }
                when (decision) {
                    is ApprovalDecision.Approved -> require(!decision.allEdits || category == ApprovalCategory.Edit) { "Approve all edits cannot approve builds or installations" }
                    is ApprovalDecision.Declined -> {
                        tools.resolve(call, decision)
                        return finish(RecordedExecution(ToolOutcome.Cancelled,
                            "The user explicitly declined this action. Do not retry it unless the user asks.", "DECLINED"))
                    }
                    is ApprovalDecision.Cancelled -> {
                        tools.resolve(call, decision)
                        finish(RecordedExecution(ToolOutcome.Cancelled))
                        throw CancellationException("Task cancelled")
                    }
                    is ApprovalDecision.Interrupted -> {
                        tools.resolve(call, decision)
                        return finish(RecordedExecution(ToolOutcome.Interrupted))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!journal.claim(key)) return finish(RecordedExecution(ToolOutcome.Interrupted))
            claimed = true
            val outcome = tools.execute(call)
            val result = RecordedExecution(outcome).let { if (it.output.length > MAX_OUTPUT) it.copy(output = it.output.take(MAX_OUTPUT) + "\n[truncated]") else it }
            return finish(result)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                tools.resolve(call, ApprovalDecision.Cancelled(key))
                journal.finish(key, RecordedExecution(if (claimed) ToolOutcome.Interrupted else ToolOutcome.Cancelled))
            }
            throw cancelled
        } catch (error: Exception) {
            if (!claimed) withContext(NonCancellable) { tools.resolve(call, ApprovalDecision.Interrupted(key)) }
            val outcome = if (error is RuntimeUnavailableException) ToolOutcome.RuntimeUnavailable(error.message.orEmpty())
                else ToolOutcome.Failed(error.message ?: error.javaClass.simpleName)
            return finish(RecordedExecution(outcome))
        }
    }

    companion object {
        const val MAX_OUTPUT = 60_000
        const val INSTRUCTIONS = "You are the coding agent inside Antigravity Mobile, working in one project stored on the user's Android phone. " +
            "Inspect files with the tools before editing. Every write or delete asks the user for approval and is recorded for review in the Changes screen. " +
            "You can build Android Gradle projects on the phone with build_project (for example :app:assembleDebug, or :app:testDebugUnitTest " +
            "to run unit tests) and install the resulting APK with install_apk; both need the user's approval and building takes minutes. " +
            "Read the build log in the result and fix errors yourself. There is no general shell or network tool; never claim results you did not get from a tool. " +
            "Paths are relative to the project root. Keep edits focused, write complete file contents with write_file, " +
            "and finish with a short summary of what changed and what the user should check."
    }
}

/** Project-bounded tools. `.git` internals are off limits; writes go through the durable change ledger. */
class WorkspaceTools(
    private val workspace: WorkspaceService,
    private val changes: ChangeService,
    private val changeSet: suspend () -> String,
    private val gitStatus: (() -> String)? = null,
) : ToolHost {
    private val reviewed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, FileBaseline>>()
    override suspend fun resolve(call: AgentItem.ToolCall, decision: ApprovalDecision) { reviewed.remove(call.callId) }
    override val specs = listOf(
        ToolSpec("list_files", "List files in the project (recursive, excluding .git). Optionally limit to a directory.",
            """{"type":"object","properties":{"path":{"type":"string","description":"Directory relative to the project root; empty for the root"}},"additionalProperties":false}"""),
        ToolSpec("read_file", "Read a UTF-8 text file. Optionally a 1-based inclusive line range.",
            """{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["path"],"additionalProperties":false}"""),
        ToolSpec("search_text", "Case-insensitive text search across project files. Returns path:line: text matches.",
            """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string","description":"Optional directory to search"}},"required":["query"],"additionalProperties":false}"""),
        ToolSpec("write_file", "Create or replace a text file with the complete new content. Requires user approval.",
            """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}"""),
        ToolSpec("delete_file", "Delete one file. Requires user approval.",
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""),
    ) + if (gitStatus != null) listOf(ToolSpec("git_status", "Show the Git branch and changed files.",
        """{"type":"object","properties":{},"additionalProperties":false}""")) else emptyList()

    override fun requiresApproval(name: String) = name == "write_file" || name == "delete_file"

    private fun args(call: AgentItem.ToolCall) = JSONObject(call.arguments.ifBlank { "{}" })
    private fun path(value: String, allowRoot: Boolean = false): String {
        val path = value.trim().trim('/').removePrefix("./")
        if (path.isEmpty()) { require(allowRoot) { "A file path is required" }; return "" }
        require(path.split('/').none { it == ".git" }) { "Git internals (.git) cannot be accessed by tools" }
        val normalized = workspace.normalize(path)
        // A symlink elsewhere in the project could point into .git; check the real target too.
        require(workspace.resolvedPath(normalized).split('/').none { it == ".git" }) { "Git internals (.git) cannot be accessed by tools" }
        return normalized
    }
    private fun text(bytes: ByteArray, path: String): String {
        require(!bytes.take(8000).contains(0.toByte())) { "$path looks like a binary file" }
        return bytes.toString(Charsets.UTF_8)
    }

    override suspend fun describe(call: AgentItem.ToolCall): ToolPreview {
        val args = args(call)
        return when (call.name) {
            "list_files" -> ToolPreview("List files ${path(args.optString("path"), true).ifEmpty { "(project root)" }}")
            "read_file" -> ToolPreview("Read ${path(args.getString("path"))}")
            "search_text" -> ToolPreview("Search for \"${args.getString("query").take(80)}\"")
            "git_status" -> ToolPreview("Check Git status")
            "write_file" -> {
                val path = path(args.getString("path"))
                val content = args.getString("content")
                require(content.length <= MAX_FILE) { "Content is larger than ${MAX_FILE / 1024} KB" }
                val exists = workspace.exists(path)
                require(!exists || !workspace.isDirectory(path)) { "$path is a directory" }
                require(!exists || workspace.size(path) <= MAX_FILE) { "$path is larger than ${MAX_FILE / 1024} KB; edit it in the editor instead" }
                val before = if (exists) workspace.readBytes(path) else null
                reviewed[call.callId] = path to FileBaseline(before)
                ToolPreview("${if (before == null) "Create" else "Edit"} $path", TextDiff.unified(path, before, content.toByteArray()))
            }
            "delete_file" -> {
                val path = path(args.getString("path"))
                require(workspace.exists(path) && !workspace.isDirectory(path)) { "$path is not an existing file" }
                require(workspace.size(path) <= MAX_FILE) { "$path is larger than ${MAX_FILE / 1024} KB; delete it from the file browser instead" }
                val before = workspace.readBytes(path)
                reviewed[call.callId] = path to FileBaseline(before)
                ToolPreview("Delete $path", TextDiff.unified(path, before, null))
            }
            else -> throw IllegalArgumentException("Unknown tool ${call.name}")
        }
    }

    override suspend fun execute(call: AgentItem.ToolCall): ToolOutcome = ToolOutcome.Success(executeText(call))

    private suspend fun executeText(call: AgentItem.ToolCall): String {
        val args = args(call)
        return when (call.name) {
            "list_files" -> {
                val start = path(args.optString("path"), true)
                require(start.isEmpty() || workspace.isDirectory(start)) { "$start is not a directory" }
                val files = workspace.walkFiles(start, 501)
                if (files.isEmpty()) "(no files)" else files.take(500).joinToString("\n") + if (files.size > 500) "\n[more files not shown]" else ""
            }
            "read_file" -> {
                val path = path(args.getString("path"))
                require(workspace.exists(path) && !workspace.isDirectory(path)) { "$path does not exist" }
                require(workspace.size(path) <= MAX_FILE) { "$path is larger than ${MAX_FILE / 1024} KB" }
                val lines = text(workspace.readBytes(path), path).lines()
                val start = args.optInt("start_line", 1).coerceAtLeast(1)
                val end = args.optInt("end_line", lines.size).coerceAtMost(lines.size)
                if (start == 1 && end == lines.size) lines.joinToString("\n")
                else (start..end).joinToString("\n") { "$it: ${lines[it - 1]}" }
            }
            "search_text" -> {
                val query = args.getString("query")
                require(query.isNotBlank()) { "Enter a search query" }
                val hits = ArrayList<String>()
                for (file in workspace.walkFiles(path(args.optString("path"), true))) {
                    if (hits.size >= 100 || workspace.size(file) > 1_000_000) continue
                    val bytes = workspace.readBytes(file)
                    if (bytes.take(8000).contains(0.toByte())) continue
                    bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                        if (hits.size < 100 && line.contains(query, ignoreCase = true)) hits += "$file:${index + 1}: ${line.trim().take(200)}"
                    }
                }
                if (hits.isEmpty()) "No matches" else hits.joinToString("\n")
            }
            "git_status" -> gitStatus?.invoke() ?: "This project is not a Git repository"
            "write_file" -> {
                val path = path(args.getString("path"))
                val content = args.getString("content")
                require(content.length <= MAX_FILE) { "Content is larger than ${MAX_FILE / 1024} KB" }
                val expected = reviewed.remove(call.callId) ?: error("Edit was not prepared for approval")
                require(expected.first == path)
                changes.apply(changeSet(), workspace, path, content.toByteArray(), expected.second)
                "Wrote $path (${content.lines().size} lines). The change is recorded for review."
            }
            "delete_file" -> {
                val path = path(args.getString("path"))
                val expected = reviewed.remove(call.callId) ?: error("Delete was not prepared for approval")
                require(expected.first == path)
                changes.apply(changeSet(), workspace, path, null, expected.second)
                "Deleted $path. The change is recorded for review."
            }
            else -> throw IllegalArgumentException("Unknown tool ${call.name}")
        }
    }

    companion object { const val MAX_FILE = 400_000 }
}
