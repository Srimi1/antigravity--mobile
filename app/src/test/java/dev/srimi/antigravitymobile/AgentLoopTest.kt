package dev.srimi.antigravitymobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import dev.srimi.antigravitymobile.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises the tool loop against a scripted model. The script exists only in tests; the shipped app has
 * no substitute provider and never fabricates responses.
 */
class AgentLoopTest {
    private class ScriptedModel(private val turns: List<List<ProviderEvent>>) : AgentModel {
        override val providerId = "test"
        val requests = mutableListOf<AgentRequest>()
        override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow {
            requests += request
            turns[requests.size - 1].forEach { emit(it) }
        }
        override fun cancel() {}
    }
    private val base = Files.createTempDirectory("agent").toFile()
    private val workspace = WorkspaceService(File(base, "project"), File(base, "checkpoints"))
    private val dao = MemoryChangeDao()
    private val changes = ChangeService(dao, File(base, "snapshots"))
    private var setId: String? = null
    private val tools = WorkspaceTools(workspace, changes, { setId ?: changes.open("p", "c", "task").id.also { setId = it } })

    private fun call(id: String, name: String, args: JSONObject) =
        ProviderEvent.Item(AgentItem.ToolCall(id, name, args.toString()))

    @Test fun readsWritesWithApprovalAndFeedsResultsBack() = runBlocking {
        workspace.write("src/App.kt", "fun app() = 1\n")
        val model = ScriptedModel(listOf(
            listOf(call("c1", "read_file", JSONObject().put("path", "src/App.kt")), ProviderEvent.Completed),
            listOf(call("c2", "write_file", JSONObject().put("path", "src/App.kt").put("content", "fun app() = 2\n")), ProviderEvent.Completed),
            listOf(ProviderEvent.Text("Done"), ProviderEvent.Item(AgentItem.Assistant("Done")), ProviderEvent.Completed),
        ))
        val finished = mutableListOf<String>()
        var approvals = 0
        val listener = object : AgentListener {
            override suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, result: RecordedExecution) {
                finished += "${call.name}:${result.status}"
            }
        }
        val items = AgentOrchestrator(model, tools, { key, _, preview ->
            approvals++; assertTrue(preview.detail.contains("+fun app() = 2")); ApprovalDecision.Approved(key)
        }, listener).run(AgentOrchestrator.INSTRUCTIONS, emptyList(), "Change app to return 2")
        assertEquals(listOf("read_file:COMPLETED", "write_file:COMPLETED"), finished)
        assertEquals(1, approvals)
        assertEquals("fun app() = 2\n", workspace.read("src/App.kt"))
        val secondInput = model.requests[1].input
        assertEquals(AgentItem.ToolResult("c1", "fun app() = 1\n"), secondInput.last())
        assertTrue(items.last() is AgentItem.Assistant)
        assertEquals(1, changes.diffs(setId!!).size)
    }

    @Test fun declinedWriteLeavesFileAndTellsModel() = runBlocking {
        workspace.write("A.kt", "keep")
        val model = ScriptedModel(listOf(
            listOf(call("c1", "delete_file", JSONObject().put("path", "A.kt")), ProviderEvent.Completed),
            listOf(ProviderEvent.Item(AgentItem.Assistant("OK")), ProviderEvent.Completed),
        ))
        AgentOrchestrator(model, tools, { key, _, _ -> ApprovalDecision.Declined(key) }, object : AgentListener {}).run("", emptyList(), "delete A")
        assertEquals("keep", workspace.read("A.kt"))
        val result = model.requests[1].input.last() as AgentItem.ToolResult
        assertTrue(result.output.contains("declined"))
        assertNull(setId)
    }
    @Test fun approvalCannotOverwriteEditMadeAfterPreview() = runBlocking {
        workspace.write("A.kt", "before")
        val model = ScriptedModel(listOf(
            listOf(call("c1", "write_file", JSONObject().put("path", "A.kt").put("content", "agent edit")), ProviderEvent.Completed),
            listOf(ProviderEvent.Item(AgentItem.Assistant("done")), ProviderEvent.Completed),
        ))
        AgentOrchestrator(model, tools, { key, _, _ ->
            workspace.write("A.kt", "owner edit")
            ApprovalDecision.Approved(key)
        }, object : AgentListener {}).run("", emptyList(), "edit")
        assertEquals("owner edit", workspace.read("A.kt"))
        assertTrue((model.requests[1].input.last() as AgentItem.ToolResult).output.contains("Later edit"))
    }

    @Test fun toolErrorsAreReportedNotThrownAndGitIsOffLimits() = runBlocking {
        val model = ScriptedModel(listOf(
            listOf(call("c1", "read_file", JSONObject().put("path", "../secret")),
                call("c2", "write_file", JSONObject().put("path", ".git/config").put("content", "x")), ProviderEvent.Completed),
            listOf(ProviderEvent.Item(AgentItem.Assistant("Sorry")), ProviderEvent.Completed),
        ))
        val statuses = mutableListOf<String>()
        AgentOrchestrator(model, tools, { key, _, _ -> fail("must not ask approval for an invalid request"); ApprovalDecision.Approved(key) }, object : AgentListener {
            override suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, result: RecordedExecution) { statuses += result.status }
        }).run("", emptyList(), "bad paths")
        assertEquals(listOf("FAILED", "FAILED"), statuses)
        val outputs = model.requests[1].input.filterIsInstance<AgentItem.ToolResult>().map { it.output }
        assertTrue(outputs.all { it.startsWith("Error:") })
    }

    @Test fun incompleteStreamFailsTheTask() {
        val model = ScriptedModel(listOf(listOf(ProviderEvent.Text("partial"))))
        assertThrows(ModelRequestFailure::class.java) {
            runBlocking { AgentOrchestrator(model, tools, { key, _, _ -> ApprovalDecision.Approved(key) }, object : AgentListener {}).run("", emptyList(), "x") }
        }
    }

    @Test fun listAndSearchSkipGitInternals() = runBlocking {
        workspace.write("src/A.kt", "val needle = 1\n")
        workspace.write(".git/HEAD", "needle")
        val list = tools.execute(AgentItem.ToolCall("1", "list_files", "{}"))
        assertEquals("src/A.kt", (list as ToolOutcome.Success).data)
        val search = tools.execute(AgentItem.ToolCall("2", "search_text", JSONObject().put("query", "NEEDLE").toString()))
        assertEquals("src/A.kt:1: val needle = 1", (search as ToolOutcome.Success).data)
        val nested = runCatching { tools.describe(AgentItem.ToolCall("3", "write_file",
            JSONObject().put("path", "vendor/lib/.git/config").put("content", "x").toString())) }
        assertTrue(nested.exceptionOrNull() is IllegalArgumentException)
    }
}
