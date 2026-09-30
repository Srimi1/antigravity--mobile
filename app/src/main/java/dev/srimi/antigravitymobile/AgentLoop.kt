package dev.srimi.antigravitymobile

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class ToolPreview(val summary: String, val detail: String = "")

/** Tools offered to a model. Anything that changes files must require approval. */
interface ToolHost {
    val specs: List<ToolSpec>
    fun requiresApproval(name: String): Boolean
    suspend fun describe(call: AgentItem.ToolCall): ToolPreview
    suspend fun execute(call: AgentItem.ToolCall): String
}

fun interface ApprovalGate { suspend fun approve(call: AgentItem.ToolCall, preview: ToolPreview): Boolean }

interface AgentListener {
    suspend fun onTextDelta(delta: String) {}
    suspend fun onAssistantMessage(text: String) {}
    suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, status: String, output: String) {}
    suspend fun onNotice(text: String) {}
}

/**
 * The provider-independent tool loop: stream a model turn, run the requested tools (after approval where
 * required), feed results back, and stop when the model answers without tool calls.
 * Nothing is retried automatically; a failure ends the task and is reported.
 */
class AgentOrchestrator(
    private val model: AgentModel,
    private val tools: ToolHost,
    private val approvals: ApprovalGate,
    private val listener: AgentListener,
    private val maxSteps: Int = 30,
) {
    suspend fun run(instructions: String, history: List<AgentItem>, prompt: String): List<AgentItem> {
        val items = history.toMutableList()
        items += AgentItem.User(prompt)
        repeat(maxSteps) {
            val produced = mutableListOf<AgentItem>()
            var completed = false
            model.streamAgentTurn(AgentRequest(instructions, items.toList(), tools.specs)).collect { event ->
                when (event) {
                    is ProviderEvent.Text -> listener.onTextDelta(event.delta)
                    is ProviderEvent.Item -> {
                        produced += event.item
                        if (event.item is AgentItem.Assistant && event.item.text.isNotBlank()) listener.onAssistantMessage(event.item.text)
                    }
                    ProviderEvent.Completed -> completed = true
                }
            }
            check(completed) { "The provider stream ended before the turn completed" }
            items += produced
            val calls = produced.filterIsInstance<AgentItem.ToolCall>()
            if (calls.isEmpty()) return items
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                items += AgentItem.ToolResult(call.callId, runTool(call))
            }
        }
        listener.onNotice("Stopped after $maxSteps model steps. Send another message to continue.")
        return items
    }

    private suspend fun runTool(call: AgentItem.ToolCall): String {
        val preview = try { tools.describe(call) } catch (error: Exception) {
            val output = "Error: ${error.message ?: error.javaClass.simpleName}"
            listener.onToolFinished(call, ToolPreview("${call.name} (invalid request)"), "FAILED", output)
            return output
        }
        if (tools.requiresApproval(call.name) && !approvals.approve(call, preview)) {
            val output = "The user declined this action. Do not retry it unless the user asks."
            listener.onToolFinished(call, preview, "DECLINED", output)
            return output
        }
        return try {
            val output = tools.execute(call).let { if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT) + "\n[truncated]" else it }
            listener.onToolFinished(call, preview, "COMPLETED", output)
            output
        } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Exception) {
            val output = "Error: ${error.message ?: error.javaClass.simpleName}"
            listener.onToolFinished(call, preview, "FAILED", output)
            output
        }
    }

    companion object {
        const val MAX_OUTPUT = 60_000
        const val INSTRUCTIONS = "You are the coding agent inside Antigravity Mobile, working in one project stored on the user's Android phone. " +
            "Inspect files with the tools before editing. Every write or delete asks the user for approval and is recorded for review in the Changes screen. " +
            "You have no shell, compiler, test runner or network tool, so you cannot build or run anything; never claim that you did. " +
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
        return workspace.normalize(path)
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
                val before = if (workspace.exists(path)) workspace.readBytes(path) else null
                require(before == null || !workspace.isDirectory(path)) { "$path is a directory" }
                ToolPreview("${if (before == null) "Create" else "Edit"} $path", TextDiff.unified(path, before, content.toByteArray()))
            }
            "delete_file" -> {
                val path = path(args.getString("path"))
                require(workspace.exists(path) && !workspace.isDirectory(path)) { "$path is not an existing file" }
                ToolPreview("Delete $path", TextDiff.unified(path, workspace.readBytes(path), null))
            }
            else -> throw IllegalArgumentException("Unknown tool ${call.name}")
        }
    }

    override suspend fun execute(call: AgentItem.ToolCall): String {
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
                changes.apply(changeSet(), workspace, path, content.toByteArray())
                "Wrote $path (${content.lines().size} lines). The change is recorded for review."
            }
            "delete_file" -> {
                val path = path(args.getString("path"))
                changes.apply(changeSet(), workspace, path, null)
                "Deleted $path. The change is recorded for review."
            }
            else -> throw IllegalArgumentException("Unknown tool ${call.name}")
        }
    }

    companion object { const val MAX_FILE = 400_000 }
}
