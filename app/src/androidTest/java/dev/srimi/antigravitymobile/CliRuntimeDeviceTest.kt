package dev.srimi.antigravitymobile

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.bridge.*
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Real Room, task router, durable transport claims and Changes import; CLI process is a fixture boundary. */
@RunWith(AndroidJUnit4::class)
class CliRuntimeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var fixtureRoot: File? = null
    private class Bridge : CliBridgeEndpoint {
        val messages = mutableListOf<JSONObject>()
        val commands = mutableSetOf<String>()
        val eventList = mutableListOf<JSONObject>()
        var id = ""
        var cwd = ""
        var phase = "NOT_FOUND"
        var content = "before"
        var unavailableReason: String? = null
        var disconnect = false
        var unconfirmed = false
        var malformed = false
        var accepts = 0
        var starts = 0
        /** Helper state reported right after start, cancel and while events are still being drained. */
        var startState = "RUNNING"
        var cancelState = "CANCELLED"
        var drained = true
        var trailingDenial = false
        var dieWhileAwaiting = false
        var cancels = 0
        var captureViolations = 0
        private val lock = Any()
        override suspend fun unavailable(backend: AgentBackend) = unavailableReason?.let(ToolOutcome::RuntimeUnavailable)
        override suspend fun prepare(snapshot: CliWorkspaceStore.Snapshot): String {
            id = snapshot.taskId; cwd = "/root/fixture/$id/source"
            ZipFile(snapshot.archive).use { zip -> content = zip.getInputStream(zip.getEntry("Game.kt")).bufferedReader().use { it.readText() } }
            return cwd
        }
        override suspend fun start(taskId: String, backend: AgentBackend, sessionId: String?): Boolean = synchronized(lock) {
            assertEquals(id, taskId); assertEquals(AgentBackend.Codex, backend); assertNull(sessionId)
            starts++; phase = startState; true
        }
        private fun event(message: JSONObject) { eventList += JSONObject().put("sequence", eventList.size + 1).put("kind", "cli").put("message", message) }
        private fun response(id: String, result: JSONObject) = JSONObject().put("id", id).put("result", result)
        private fun notification(method: String, params: JSONObject) = JSONObject().put("method", method).put("params", params)
        override suspend fun send(taskId: String, commandId: String, message: JSONObject): Boolean = synchronized(lock) {
            assertEquals(id, taskId); assertTrue("native runner resent a transport claim", commands.add(commandId))
            messages += message
            when (message.optString("method")) {
                "initialize" -> event(response("agm-init", JSONObject()))
                "initialized" -> Unit
                "thread/start" -> event(response("agm-thread", JSONObject().put("thread", JSONObject().put("id", "thread-1"))
                    .put("cwd", cwd).put("sandbox", JSONObject().put("type", "workspaceWrite").put("networkAccess", false))
                    .put("approvalPolicy", "on-request").put("approvalsReviewer", "user")))
                "turn/start" -> {
                    event(response("agm-turn", JSONObject().put("turn", JSONObject().put("id", "turn-1"))))
                    event(notification("item/commandExecution/requestApproval", JSONObject().put("threadId", "thread-1")
                        .put("turnId", "turn-1").put("itemId", "command-1").put("command", "fixture command").put("cwd", cwd)).put("id", 7))
                    if (dieWhileAwaiting) {
                        eventList += JSONObject().put("sequence", eventList.size + 1).put("kind", "exit").put("code", 1)
                        phase = "EXITED"
                    }
                }
                else -> {
                    assertEquals(7, message.getInt("id"))
                    val decision = message.getJSONObject("result").getString("decision")
                    if (decision == "accept") { accepts++; content = "after" }
                    val item = JSONObject().put("id", "command-1").put("type", "commandExecution")
                        .put("status", if (decision == "accept") "completed" else "declined").put("exitCode", 0).put("aggregatedOutput", "fixture CLI output")
                    event(notification("item/completed", JSONObject().put("threadId", if (malformed) "other-thread" else "thread-1")
                        .put("turnId", "turn-1").put("item", item)))
                    event(notification("item/completed", JSONObject().put("threadId", "thread-1").put("turnId", "turn-1")
                        .put("item", JSONObject().put("id", "reply").put("type", "agentMessage").put("text", "Recorded fixture CLI answer"))))
                    event(notification("turn/completed", JSONObject().put("threadId", "thread-1")
                        .put("turn", JSONObject().put("id", "turn-1").put("status", "completed"))))
                }
            }
            true
        }
        private fun state(task: String) = CliWorkerState(task, phase, eventList.size.toLong(), null, unconfirmed,
            drained || phase !in setOf("EXITED", "CANCELLED", "INTERRUPTED"))
        override suspend fun observe(taskId: String, after: Long): CliPage = synchronized(lock) {
            if (disconnect && after >= 4) { disconnect = false; throw IOException("fixture disconnection") }
            CliPage(state(taskId), eventList.drop(after.toInt()).toList())
        }
        override suspend fun status(taskId: String) = synchronized(lock) { state(taskId) }
        override suspend fun cancel(taskId: String) = synchronized(lock) {
            cancels++
            if (phase in setOf("RUNNING", "STARTING", "CANCEL_REQUESTED")) {
                phase = cancelState
                // The CLI's stderr reader can still deliver a denial diagnostic after its terminal turn.
                if (trailingDenial) { trailingDenial = false; eventList += JSONObject().put("sequence", eventList.size + 1).put("kind", "permission_unavailable") }
            }
            state(taskId)
        }
        override suspend fun capture(taskId: String, destination: File): File {
            // Capturing while the CLI may still write would import a moving target.
            if (phase !in setOf("CANCELLED", "EXITED") || !drained) { captureViolations++; throw IOException("capture before termination") }
            destination.parentFile!!.mkdirs()
            ZipOutputStream(destination.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("Game.kt")); zip.write(content.toByteArray()); zip.closeEntry() }
            return destination
        }
    }
    private suspend fun fixture(block: suspend (AppContainer, ProjectRecord, Bridge, CoroutineScope) -> Unit) {
        val store = Room.inMemoryDatabaseBuilder(context, SessionStore::class.java).build()
        val bridge = Bridge()
        val root = File(context.cacheDir, "cli-test-${UUID.randomUUID()}").apply { mkdirs() }
        val execution = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val model = object : AgentModel {
            override val providerId = "fixture"
            override fun cancel() {}
            override fun streamAgentTurn(request: AgentRequest) = flow { emit(ProviderEvent.Item(AgentItem.Assistant("native fixture"))); emit(ProviderEvent.Completed) }
        }
        val services = AppContainer(context, store,
            cliFactory = { services, app -> CliAgentTaskRunner(services, app, bridge,
                CliWorkspaceStore(File(root, "sources")), CliEventStore(File(root, "events")), File(root, "results"), dispatch = {},
                livenessInterval = 50, cleanupTimeout = 1_000) },
            taskFactory = { services, app -> NativeAgentTaskRunner(services, app, modelFor = { model }, dispatch = {}) })
        services.ready.await()
        val project = services.projects.create("CLI fixture ${UUID.randomUUID()}") { File(it, "Game.kt").writeText("before") }
        fixtureRoot = root
        try { block(services, project, bridge, execution) }
        finally {
            bridge.unconfirmed = false
            services.database.runtime().active()?.let { services.tasks.cancel(it.id) }
            execution.cancel(); services.scope.cancel(); services.projects.delete(project); store.close(); root.deleteRecursively()
        }
    }
    private suspend fun waitFor(services: AppContainer, id: String, phase: TaskPhase) = withTimeout(15_000) {
        services.database.runtime().observeTask(id).filterNotNull().first { it.status == phase.name }
    }
    private suspend fun prompt(services: AppContainer, id: String, tool: String) = withTimeout(15_000) {
        services.database.runtime().observeActions(id).map { list -> list.firstOrNull { it.tool == tool && it.status == "AWAITING_APPROVAL" } }.filterNotNull().first()
    }
    private suspend fun start(services: AppContainer, project: ProjectRecord, scope: CoroutineScope) =
        services.tasks.start(TaskStart(project.id, null, "CLI fixture task", "fixture", AgentBackend.Codex)).also { services.tasks.launchFromService(it, scope) }

    @Test fun duplicateApprovalsSendOnceAndImportThroughChanges() = runBlocking {
        fixture { services, project, bridge, scope ->
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command")
            waitFor(services, id, TaskPhase.AwaitingApproval)
            assertFalse(services.tasks.answerApproval(ApprovalDecision.Approved(command.key().copy(buildId = "stale"))))
            assertFalse(services.tasks.answerApproval(ApprovalDecision.Approved(command.key(), allEdits = true)))
            assertEquals(1, coroutineScope { (1..10).map { async { services.tasks.answerApproval(ApprovalDecision.Approved(command.key())) } }.awaitAll().count { it } })
            val returned = prompt(services, id, "import_cli_changes")
            waitFor(services, id, TaskPhase.AwaitingApproval)
            assertEquals("before", services.workspace(project).read("Game.kt"))
            assertTrue(returned.preview.contains("after"))
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(returned.key())))
            val completed = waitFor(services, id, TaskPhase.Completed)
            assertEquals(1, bridge.starts); assertEquals(1, bridge.accepts)
            assertEquals("after", services.workspace(project).read("Game.kt"))
            assertEquals("REVIEW", services.database.changes().findSet(completed.changeSetId!!)!!.status)
            assertEquals("before", services.changes.diffs(completed.changeSetId).single().before!!.toString(Charsets.UTF_8))
            assertEquals("COMPLETED", services.database.runtime().action(id, command.toolCallId)!!.status)
            val file = File(services.projects.directory(project), "Game.kt"); val at = file.lastModified()
            services.tasks.retry(id); delay(100)
            assertEquals(at, file.lastModified()); assertEquals(1, bridge.accepts)
            assertTrue(services.database.conversations().messages(completed.conversationId).any { it.content == "Recorded fixture CLI answer" })
        }
    }
    @Test fun disconnectedBridgeAndDeathRecoveryNeverResendApprovedCommand() = runBlocking {
        fixture { services, project, bridge, scope ->
            bridge.disconnect = true
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command"); waitFor(services, id, TaskPhase.AwaitingApproval)
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(command.key())))
            val paused = waitFor(services, id, TaskPhase.Paused)
            assertTrue(paused.detail.startsWith("Paused:")); assertEquals("after", bridge.content)
            assertEquals("before", services.workspace(project).read("Game.kt"))
            services.tasks.awaitIdle(); services.tasks.recover()
            assertEquals("INTERRUPTED", services.database.runtime().action(id, command.toolCallId)!!.status)
            services.tasks.retry(id); services.tasks.launchFromService(id, scope)
            val returned = prompt(services, id, "import_cli_changes"); waitFor(services, id, TaskPhase.AwaitingApproval)
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(returned.key())))
            waitFor(services, id, TaskPhase.Completed)
            assertEquals(1, bridge.starts); assertEquals(1, bridge.accepts)
            assertEquals("COMPLETED", services.database.runtime().action(id, command.toolCallId)!!.status)
        }
    }
    @Test fun deniedPermissionNeverBecomesDeclineOrStartsCli() = runBlocking {
        fixture { services, project, bridge, scope ->
            bridge.unavailableReason = "Termux RUN_COMMAND permission denied"
            val id = start(services, project, scope)
            val failed = waitFor(services, id, TaskPhase.Failed)
            assertNull(failed.activeSlot); assertEquals(0, bridge.starts)
            assertTrue(failed.detail.contains("Runtime unavailable"))
            assertTrue(services.database.runtime().actions(id).isEmpty())
            assertFalse(services.database.conversations().messages(failed.conversationId).any { it.content.contains("user declined", true) })
        }
    }
    @Test fun stopKeepsSlotUntilCliTerminationConfirmedAcrossBackends() = runBlocking {
        fixture { services, project, bridge, scope ->
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command"); waitFor(services, id, TaskPhase.AwaitingApproval)
            bridge.unconfirmed = true; services.tasks.cancel(id)
            assertEquals(1, services.database.runtime().task(id)!!.activeSlot)
            assertEquals("CANCELLED", services.database.runtime().action(id, command.toolCallId)!!.decision)
            assertEquals(0, bridge.accepts)
            try { services.tasks.start(TaskStart(project.id, null, "next", "fixture")); fail("unconfirmed CLI released slot") }
            catch (_: IllegalStateException) { }
            services.tasks.recover()
            assertEquals(1, services.database.runtime().task(id)!!.activeSlot)
            bridge.unconfirmed = false; services.tasks.cancel(id)
            assertNull(services.database.runtime().task(id)!!.activeSlot)
            val next = services.tasks.start(TaskStart(project.id, null, "next", "fixture"))
            services.tasks.launchFromService(next, scope); waitFor(services, next, TaskPhase.Completed)
        }
    }
    @Test fun conflictingImportPreservesOwnerFileAndMalformedEventPauses() = runBlocking {
        for (badEvent in listOf(false, true)) fixture { services, project, bridge, scope ->
            bridge.malformed = badEvent
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command"); waitFor(services, id, TaskPhase.AwaitingApproval)
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(command.key())))
            if (!badEvent) {
                val returned = prompt(services, id, "import_cli_changes"); waitFor(services, id, TaskPhase.AwaitingApproval)
                services.workspace(project).write("Game.kt", "owner edit")
                assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(returned.key())))
            }
            waitFor(services, id, TaskPhase.Paused)
            assertEquals(if (badEvent) "before" else "owner edit", services.workspace(project).read("Game.kt"))
            assertEquals(1, bridge.accepts)
        }
    }

    @Test fun slotStaysHeldWhenHelperIsNotTerminalAfterStart() = runBlocking {
        fixture { services, project, bridge, scope ->
            bridge.startState = "STARTING"
            val id = start(services, project, scope)
            val paused = waitFor(services, id, TaskPhase.Paused)
            assertEquals(1, paused.activeSlot); assertEquals(1, bridge.starts)
        }
    }
    @Test fun slotStaysHeldUntilCleanupIsTerminalAndDrained() = runBlocking {
        fixture { services, project, bridge, scope ->
            bridge.cancelState = "CANCEL_REQUESTED"
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command"); waitFor(services, id, TaskPhase.AwaitingApproval)
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Declined(command.key())))
            val paused = waitFor(services, id, TaskPhase.Paused)
            assertEquals(1, paused.activeSlot)
            // Helper says CANCELLED without a pending flag, but its readers have not drained yet.
            services.tasks.awaitIdle(); bridge.phase = "CANCELLED"; bridge.drained = false
            services.tasks.cancel(id)
            assertEquals(1, services.database.runtime().task(id)!!.activeSlot)
            bridge.drained = true; services.tasks.cancel(id)
            assertNull(services.database.runtime().task(id)!!.activeSlot)
            assertEquals(0, bridge.captureViolations)
        }
    }
    @Test fun helperDeathWhileAwaitingApprovalClosesPromptAndPauses() = runBlocking {
        fixture { services, project, bridge, scope ->
            bridge.dieWhileAwaiting = true
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command")
            val paused = waitFor(services, id, TaskPhase.Paused)
            assertEquals(1, paused.activeSlot)
            val action = services.database.runtime().action(id, command.toolCallId)!!
            assertEquals("INTERRUPTED", action.decision)
            assertFalse(services.tasks.answerApproval(ApprovalDecision.Approved(command.key())))
            assertEquals(0, bridge.accepts)
        }
    }
    @Test fun incompletePreDispatchSnapshotIsRebuiltAfterDeath() = runBlocking {
        fixture { services, project, bridge, scope ->
            val id = services.tasks.start(TaskStart(project.id, null, "CLI fixture task", "fixture", AgentBackend.Codex))
            // Process died inside CliWorkspaceStore.create: directory reserved, manifest never written.
            val reserved = File(fixtureRoot!!, "sources/$id")
            File(reserved, "before").mkdirs(); File(reserved, "manifest.tmp").writeText("{")
            services.tasks.launchFromService(id, scope)
            prompt(services, id, "cli_command"); waitFor(services, id, TaskPhase.AwaitingApproval)
            assertEquals(1, bridge.starts); assertEquals("before", bridge.content)
        }
    }
    @Test fun trailingPermissionDenialAfterTerminalNeverBecomesSuccess() = runBlocking {
        fixture { services, project, bridge, scope ->
            bridge.trailingDenial = true
            val id = start(services, project, scope)
            val command = prompt(services, id, "cli_command"); waitFor(services, id, TaskPhase.AwaitingApproval)
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Declined(command.key())))
            val failed = waitFor(services, id, TaskPhase.Failed)
            assertNull(failed.activeSlot)
            assertTrue(failed.detail, failed.detail.contains("permission", true))
            assertFalse(services.database.conversations().messages(failed.conversationId).any { it.content.contains("user declined", true) && it.content.contains("permission", true) })
        }
    }
}
