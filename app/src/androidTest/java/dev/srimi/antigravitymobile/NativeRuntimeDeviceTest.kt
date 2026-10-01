package dev.srimi.antigravitymobile

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.providers.ProviderFailure
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Room, change ledger and runner; provider and worker are the external fake boundaries. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Model : AgentModel {
        override val providerId = "fixture"
        val requests = mutableListOf<AgentRequest>()
        var turn: suspend FlowCollector<ProviderEvent>.(AgentRequest, Int) -> Unit = { _, _ ->
            emit(ProviderEvent.Item(AgentItem.Assistant("done"))); emit(ProviderEvent.Completed)
        }
        override fun streamAgentTurn(request: AgentRequest) = flow { requests += request; turn(request, requests.size) }
        override fun cancel() {}
    }
    private class Worker : BuildRunner {
        val started = mutableListOf<String>()
        val resolved = mutableListOf<ApprovalDecision>()
        override fun unavailableReason(): String? = null
        override suspend fun prepare(tasks: String) = PreparedBuild(UUID.randomUUID().toString(), tasks, "snapshot")
        override suspend fun resolvePending(id: String, decision: ApprovalDecision) { resolved += decision }
        override suspend fun runApproved(id: String): BuildOutcome { started += id; return awaitExisting(id) }
        override suspend fun awaitExisting(id: String) = BuildOutcome(id, "FAILED", "actual fixture worker failure", 1,
            emptyList(), "BUILD FAILED: fixture error", "fixture-log")
        override suspend fun recorded(id: String?, successfulOnly: Boolean): BuildOutcome? = null
        override suspend fun install(buildId: String, apk: String?) = error("No fixture APK")
    }
    private suspend fun fixture(configure: (RoomDatabase.Builder<SessionStore>) -> Unit = {},
        block: suspend (AppContainer, ProjectRecord, Model, Worker, CoroutineScope) -> Unit) {
        val store = Room.inMemoryDatabaseBuilder(context, SessionStore::class.java).also(configure).build()
        val model = Model(); val worker = Worker(); val execution = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val services = AppContainer(context, store) { services, app -> NativeAgentTaskRunner(services, app,
            modelFor = { model }, buildFor = { _, _ -> worker }, dispatch = {}) }
        services.ready.await()
        val project = services.projects.create("Runtime fixture ${UUID.randomUUID()}") { }
        try { block(services, project, model, worker, execution) }
        finally {
            services.database.runtime().active()?.let { services.tasks.cancel(it.id) }
            execution.cancel(); services.scope.cancel()
            services.projects.delete(project); store.close()
        }
    }
    private suspend fun waitFor(services: AppContainer, id: String, phase: TaskPhase) = withTimeout(15_000) {
        services.database.runtime().observeTask(id).filterNotNull().first { it.status == phase.name }
    }
    private suspend fun start(services: AppContainer, project: ProjectRecord, scope: CoroutineScope): String =
        services.tasks.start(TaskStart(project.id, null, "fixture task", "FIXTURE")).also { services.tasks.launchFromService(it, scope) }

    @Test fun approvingBuildOnceStartsOnceAndPersistsActualFailure() = runBlocking {
        fixture { services, project, model, worker, scope ->
            model.turn = { request, index ->
                if (index == 1) emit(ProviderEvent.Item(AgentItem.ToolCall("build-call", "build_project", "{}")))
                else {
                    assertTrue(request.input.filterIsInstance<AgentItem.ToolResult>().single().output.contains("BUILD FAILED"))
                    emit(ProviderEvent.Item(AgentItem.Assistant("recorded failure")))
                }
                emit(ProviderEvent.Completed)
            }
            val id = start(services, project, scope)
            waitFor(services, id, TaskPhase.AwaitingApproval)
            val action = services.database.runtime().actions(id).single()
            assertFalse(services.tasks.answerApproval(ApprovalDecision.Approved(action.key().copy(buildId = "stale"))))
            assertFalse(services.tasks.answerApproval(ApprovalDecision.Approved(action.key(), allEdits = true)))
            assertEquals(1, coroutineScope { (1..10).map { async { services.tasks.answerApproval(ApprovalDecision.Approved(action.key())) } }.awaitAll().count { it } })
            waitFor(services, id, TaskPhase.Completed)
            assertEquals(listOf(action.buildId), worker.started)
            val recorded = services.database.runtime().action(id, "build-call")!!
            assertEquals("APPROVED", recorded.decision); assertEquals("FAILED", recorded.status)
            assertTrue(RuntimeCodec.outcome(recorded.outcome!!) is ToolOutcome.Failed)
        }
    }

    @Test fun stopAndExplicitDeclineHaveDifferentReceiptsAndNeverStartBuild() = runBlocking {
        for (decline in listOf(false, true)) fixture { services, project, model, worker, scope ->
            model.turn = { _, index ->
                emit(ProviderEvent.Item(if (index == 1) AgentItem.ToolCall("build-call", "build_project", "{}") else AgentItem.Assistant("declined")))
                emit(ProviderEvent.Completed)
            }
            val id = start(services, project, scope); waitFor(services, id, TaskPhase.AwaitingApproval)
            val action = services.database.runtime().actions(id).single()
            if (decline) { assertTrue(services.tasks.answerApproval(ApprovalDecision.Declined(action.key()))); waitFor(services, id, TaskPhase.Completed) }
            else services.tasks.cancel(id)
            val recorded = services.database.runtime().action(id, "build-call")!!
            assertEquals(if (decline) "DECLINED" else "CANCELLED", recorded.decision)
            assertEquals(decline, recorded.resultText!!.contains("user explicitly declined"))
            assertTrue(worker.started.isEmpty())
            assertEquals(if (decline) 2 else 1, model.requests.size)
        }
    }

    @Test fun providerFailuresPauseWithEditsIntactAndRetryOnlyUnfinishedRequest() = runBlocking {
        val failures = listOf(ProviderFailure.Dns("fixture"), ProviderFailure.NoNetwork("fixture"),
            ProviderFailure.Timeout("fixture"), ProviderFailure.StreamInterrupted("fixture"))
        for (failure in failures) fixture { services, project, model, _, scope ->
            model.turn = { request, index ->
                when (index) {
                    1 -> { emit(ProviderEvent.Item(AgentItem.ToolCall("write-call", "write_file", """{"path":"saved.txt","content":"kept edit"}"""))); emit(ProviderEvent.Completed) }
                    2 -> { emit(ProviderEvent.Text("partial text must not commit")); throw failure }
                    else -> {
                        assertTrue(request.input.filterIsInstance<AgentItem.ToolResult>().single().output.isNotEmpty())
                        emit(ProviderEvent.Item(AgentItem.Assistant("continued safely"))); emit(ProviderEvent.Completed)
                    }
                }
            }
            val id = start(services, project, scope); waitFor(services, id, TaskPhase.AwaitingApproval)
            val action = services.database.runtime().actions(id).single()
            assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(action.key(), allEdits = true)))
            val paused = waitFor(services, id, TaskPhase.Paused)
            assertTrue(paused.detail.startsWith("Paused: ")); assertNotNull(paused.recoveryAction)
            val file = services.workspace(project).rootDirectory.resolve("saved.txt"); val changedAt = file.lastModified()
            assertEquals("kept edit", file.readText())
            assertFalse(RuntimeCodec.items(paused.transcript).any { it is AgentItem.Assistant && it.text.contains("partial") })
            services.tasks.retry(id); services.tasks.launchFromService(id, scope)
            waitFor(services, id, TaskPhase.Completed)
            assertEquals(changedAt, file.lastModified()); assertEquals("kept edit", file.readText())
            assertEquals(1, services.database.runtime().actions(id).size)
            assertEquals(3, model.requests.size)
        }
    }

    @Test fun stopAfterRecoveryCancelsPersistedRunningBuildWithoutExecutionJob() = runBlocking {
        for (alreadyCancelled in listOf(false, true)) fixture { services, project, _, _, _ ->
            val taskId = UUID.randomUUID().toString(); val buildId = UUID.randomUUID().toString()
            val conversation = ConversationRecord("recovered-chat", project.id, "recovered", "FIXTURE", "PAUSED", 1, 1)
            services.database.conversations().save(conversation)
            services.database.runtime().createTask(RuntimeTaskRecord(taskId, project.id, conversation.id, "FIXTURE", "Native",
                "build", if (alreadyCancelled) TaskPhase.Cancelled.name else TaskPhase.Paused.name, "restored after death", null, "[]", 1, "Model", 0, null, false, 1, 1, 1))
            services.database.runtime().createAction(RuntimeActionRecord(UUID.randomUUID().toString(), taskId, "recovered-build",
                "build_project", "{}", "recovered build", "snapshot", "Build", buildId, "RUNNING", "APPROVED", 1, null, null, 1, 1))
            services.database.builds().save(BuildRecord(buildId, project.id, "help", "RUNNING", "worker still running", "hash", 1, agentTaskId = taskId))
            if (alreadyCancelled) services.tasks.recover() else services.tasks.cancel(taskId)
            assertNotEquals("RUNNING", services.database.builds().find(buildId)!!.status)
            assertEquals(TaskPhase.Cancelled.name, services.database.runtime().task(taskId)!!.status)
            assertEquals("APPROVED", services.database.runtime().action(taskId, "recovered-build")!!.decision)
        }
    }

    @Test fun nextTaskWaitsForPreviousConversationCleanupWithoutHandoffCrash() = runBlocking {
        for (cancelPrevious in listOf(false, true)) {
        val completed = AtomicBoolean(); val blocked = AtomicBoolean()
        val cleanupEntered = CountDownLatch(1); val releaseCleanup = CountDownLatch(1)
        fixture(configure = { builder -> builder.setQueryCallback(RoomDatabase.QueryCallback { sql, args ->
            if (sql.startsWith("UPDATE runtime_tasks") && args.contains(TaskPhase.Completed.name)) completed.set(true)
            if (completed.get() && sql.startsWith("SELECT * FROM conversations WHERE id =") && blocked.compareAndSet(false, true)) {
                cleanupEntered.countDown(); check(releaseCleanup.await(10, TimeUnit.SECONDS))
            }
        }, { it.run() }) }) { services, project, _, _, scope ->
            try {
                val first = start(services, project, scope); waitFor(services, first, TaskPhase.Completed)
                assertTrue(withContext(Dispatchers.IO) { cleanupEntered.await(5, TimeUnit.SECONDS) })
                if (cancelPrevious) scope.coroutineContext[Job]!!.children.single().cancel()
                val next = async(Dispatchers.IO) { services.tasks.start(TaskStart(project.id, null, "next task", "FIXTURE")) }
                delay(100); assertFalse(next.isCompleted)
                releaseCleanup.countDown()
                val second = next.await(); services.tasks.launchFromService(second, scope)
                waitFor(services, second, TaskPhase.Completed)
            } finally { releaseCleanup.countDown() }
        }
        }
    }
}
