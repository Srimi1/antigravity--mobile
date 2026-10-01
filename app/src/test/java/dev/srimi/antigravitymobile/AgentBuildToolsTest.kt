package dev.srimi.antigravitymobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import dev.srimi.antigravitymobile.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Build/install tools through the real tool loop, with a fake runner standing in for the phone's build worker. */
class AgentBuildToolsTest {
    private class Script(private val turns: List<List<ProviderEvent>>) : AgentModel {
        override val providerId = "test"
        val requests = mutableListOf<AgentRequest>()
        override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow { requests += request; turns[requests.size - 1].forEach { emit(it) } }
        override fun cancel() {}
    }
    private class FakeRunner(var reason: String? = null, val status: String = "COMPLETED") : BuildRunner {
        val log = mutableListOf<String>()
        override fun unavailableReason() = reason
        override suspend fun prepare(tasks: String) = PreparedBuild("build-${log.size}", tasks, "a".repeat(64)).also { log += "prepare $tasks" }
        override suspend fun resolvePending(id: String, decision: ApprovalDecision) { log += "${if (decision is ApprovalDecision.Declined) "decline" else "resolve"} $id" }
        var last: BuildOutcome? = null
        override suspend fun awaitExisting(id: String) = last ?: error("No build")
        override suspend fun recorded(id: String?, successfulOnly: Boolean) = last?.takeIf { (id == null || id == it.id) && (!successfulOnly || it.status == "COMPLETED") }
        override suspend fun runApproved(id: String): BuildOutcome { log += "run $id"
            return BuildOutcome(id, status, "Gradle finished", 188_000, if (status == "COMPLETED") listOf("artifact-0.apk") else emptyList(),
                if (status == "COMPLETED") "BUILD SUCCESSFUL" else "e: MainActivity.kt:12: Unresolved reference").also { last = it } }
        override suspend fun install(buildId: String, apk: String?): String { log += "install $buildId $apk"; return "Installer opened" }
    }
    private object NoFiles : ToolHost {
        override val specs = emptyList<ToolSpec>()
        override fun requiresApproval(name: String) = false
        override suspend fun describe(call: AgentItem.ToolCall) = error("unknown tool ${call.name}")
        override suspend fun execute(call: AgentItem.ToolCall): ToolOutcome = error("unknown tool ${call.name}")
    }
    private fun call(id: String, name: String, args: JSONObject = JSONObject()) = ProviderEvent.Item(AgentItem.ToolCall(id, name, args.toString()))
    private val done = listOf(ProviderEvent.Item(AgentItem.Assistant("ok")), ProviderEvent.Completed)

    @Test fun approvedBuildRunsOnceThenInstallsItsApk() = runBlocking {
        val runner = FakeRunner()
        val model = Script(listOf(listOf(call("b", "build_project"), ProviderEvent.Completed),
            listOf(call("i", "install_apk"), ProviderEvent.Completed), done))
        val previews = mutableListOf<ToolPreview>()
        AgentOrchestrator(model, AgentBuildTools(NoFiles, runner), { key, _, p -> previews += p; ApprovalDecision.Approved(key) }, object : AgentListener {})
            .run("", emptyList(), "build and install")
        assertEquals(listOf("prepare :app:assembleDebug", "run build-0", "install build-0 artifact-0.apk"), runner.log)
        assertTrue(previews[0].detail.contains("Snapshot SHA-256: " + "a".repeat(64)))
        val result = (model.requests[1].input.last() as AgentItem.ToolResult).output
        assertTrue(result, result.contains("COMPLETED in 188s") && result.contains("artifact-0.apk") && result.contains("BUILD SUCCESSFUL"))
        assertTrue(model.requests[0].tools.any { it.name == "build_project" } && model.requests[0].tools.any { it.name == "install_apk" })
    }
    @Test fun declinedBuildIsReleasedAndNeverRuns() = runBlocking {
        val runner = FakeRunner()
        val model = Script(listOf(listOf(call("b", "build_project", JSONObject().put("tasks", ":app:testDebugUnitTest")), ProviderEvent.Completed), done))
        AgentOrchestrator(model, AgentBuildTools(NoFiles, runner), { key, _, _ -> ApprovalDecision.Declined(key) }, object : AgentListener {}).run("", emptyList(), "test")
        assertEquals(listOf("prepare :app:testDebugUnitTest", "decline build-0"), runner.log)
        assertTrue((model.requests[1].input.last() as AgentItem.ToolResult).output.contains("declined"))
    }
    @Test fun stopCancelsApprovalWithoutReportingUserDeclineOrStartingBuild() = runBlocking {
        val runner = FakeRunner()
        val model = Script(listOf(listOf(call("b", "build_project"), ProviderEvent.Completed), done))
        val finished = mutableListOf<RecordedExecution>()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                AgentOrchestrator(model, AgentBuildTools(NoFiles, runner), { key, _, _ -> ApprovalDecision.Cancelled(key) }, object : AgentListener {
                    override suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, result: RecordedExecution) { finished += result }
                }).run("", emptyList(), "build")
            }
        }
        assertEquals(listOf("prepare :app:assembleDebug", "resolve build-0"), runner.log)
        assertEquals("CANCELLED", finished.single().status)
        assertFalse(finished.single().output.contains("user declined", ignoreCase = true))
        assertEquals(1, model.requests.size)
    }
    @Test fun failedBuildReturnsLogAndCannotBeInstalled() = runBlocking {
        val runner = FakeRunner(status = "FAILED")
        val statuses = mutableListOf<String>()
        val model = Script(listOf(listOf(call("b", "build_project"), ProviderEvent.Completed),
            listOf(call("i", "install_apk"), ProviderEvent.Completed), done))
        AgentOrchestrator(model, AgentBuildTools(NoFiles, runner), { key, _, _ -> ApprovalDecision.Approved(key) }, object : AgentListener {
            override suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, result: RecordedExecution) { statuses += result.status }
        }).run("", emptyList(), "build")
        assertEquals("A failed worker build must not appear completed", "FAILED", statuses.first())
        assertTrue((model.requests[1].input.last() as AgentItem.ToolResult).output.contains("Unresolved reference"))
        assertTrue((model.requests[2].input.last() as AgentItem.ToolResult).output.contains("No successful build"))
        assertFalse(runner.log.any { it.startsWith("install") })
    }
    @Test fun missingToolsAndBadTasksAreRefusedBeforeApproval() = runBlocking {
        var asked = 0
        val missing = FakeRunner(reason = "The build tools are not installed.")
        val first = Script(listOf(listOf(call("b", "build_project"), ProviderEvent.Completed), done))
        AgentOrchestrator(first, AgentBuildTools(NoFiles, missing), { key, _, _ -> asked++; ApprovalDecision.Approved(key) }, object : AgentListener {}).run("", emptyList(), "build")
        assertTrue((first.requests[1].input.last() as AgentItem.ToolResult).output.contains("not installed"))
        val ready = FakeRunner()
        val second = Script(listOf(listOf(call("c", "build_project", JSONObject().put("tasks", "--init-script evil.gradle")), ProviderEvent.Completed), done))
        AgentOrchestrator(second, AgentBuildTools(NoFiles, ready), { key, _, _ -> asked++; ApprovalDecision.Approved(key) }, object : AgentListener {}).run("", emptyList(), "build")
        assertTrue((second.requests[1].input.last() as AgentItem.ToolResult).output.contains("Gradle task names"))
        assertEquals(0, asked)
        assertTrue(missing.log.isEmpty() && ready.log.isEmpty())
    }
}
