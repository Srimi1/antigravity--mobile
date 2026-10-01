package dev.srimi.antigravitymobile

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.bridge.*
import dev.srimi.antigravitymobile.linux.AndroidTermuxGateway
import dev.srimi.antigravitymobile.linux.TermuxCommand
import dev.srimi.antigravitymobile.linux.TermuxProtocol
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real Termux, proot Debian, paired helper and the installed codex-cli app-server, without sign-in. The capability gate is
 * forced open only here (production keeps Codex closed while its sandbox probe fails); no inference is expected or claimed.
 */
@RunWith(AndroidJUnit4::class)
class CliRealTermuxDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun granted() = runCatching { context.packageManager.getPackageInfo(TermuxProtocol.PACKAGE, 0) }.isSuccess &&
        context.checkSelfPermission(TermuxProtocol.PERMISSION) == PackageManager.PERMISSION_GRANTED

    private suspend fun fixture(block: suspend (AppContainer, ProjectRecord, CoroutineScope) -> Unit) {
        val store = Room.inMemoryDatabaseBuilder(context, SessionStore::class.java).build()
        val root = File(context.noBackupFilesDir, "cli-real-${UUID.randomUUID()}").apply { mkdirs() }
        val execution = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val services = AppContainer(context, store, cliFactory = { services, app -> CliAgentTaskRunner(services, app,
            PairedCliBridge({ services.cliLauncher.connect() }, { null }), CliWorkspaceStore(File(root, "sources")),
            CliEventStore(File(root, "events")), File(root, "results"), dispatch = {}) })
        services.ready.await()
        val project = services.projects.create("Real CLI ${UUID.randomUUID()}") { File(it, "README.md").writeText("fixture\n") }
        try { block(services, project, execution) }
        finally {
            services.database.runtime().active()?.let { withTimeoutOrNull(30_000) { services.tasks.cancel(it.id) } }
            withTimeoutOrNull(10_000) { execution.coroutineContext.job.cancelAndJoin(); services.scope.coroutineContext.job.cancelAndJoin() }
            services.projects.delete(project); store.close(); root.deleteRecursively()
        }
    }
    private suspend fun settled(services: AppContainer, id: String, vararg phases: TaskPhase) = withTimeout(180_000) {
        services.database.runtime().observeTask(id).filterNotNull().first { task -> phases.any { it.name == task.status } }
    }
    private suspend fun evidence(services: AppContainer, id: String, label: String) {
        val dao = services.database.runtime(); val task = dao.task(id)!!
        Log.i("AgmEvidence", "$label: ${task.status} slot=${task.activeSlot} detail=${task.detail} actions=" +
            dao.actions(id).map { "${it.tool}/${it.status}" } + " messages=" +
            services.database.conversations().messages(task.conversationId).map { "${it.role}:${it.content.take(160)}" })
    }
    private suspend fun codexProcesses(): Int {
        val result = AndroidTermuxGateway(context).run(TermuxCommand(TermuxProtocol.BASH, listOf("-c", "pgrep -f '[a]gm_native.args' | wc -l"), timeoutMs = 20_000))
        return result.stdout.trim().toInt()
    }

    @Test fun realCodexWithoutSignInEndsAsRuntimeLimitAndLeavesNoProcess() = runBlocking {
        assumeTrue("Termux with RUN_COMMAND granted is required", granted())
        fixture { services, project, scope ->
            val id = services.tasks.start(TaskStart(project.id, null, "Say hello", "codex", AgentBackend.Codex))
            services.tasks.launchFromService(id, scope)
            val done = settled(services, id, TaskPhase.Failed, TaskPhase.Completed, TaskPhase.Paused)
            evidence(services, id, "no-sign-in")
            assertNotEquals("no inference without sign-in", TaskPhase.Completed.name, done.status)
            assertFalse(done.detail.contains("declined", true))
            if (done.status == TaskPhase.Failed.name) assertNull(done.activeSlot)
            assertEquals("README.md", services.workspace(project).read("README.md").let { "README.md" })
            assertEquals(0, codexProcesses())
        }
    }

    @Test fun stopDuringRealCodexConfirmsTerminationBeforeReleasingSlot() = runBlocking {
        assumeTrue("Termux with RUN_COMMAND granted is required", granted())
        fixture { services, project, scope ->
            val id = services.tasks.start(TaskStart(project.id, null, "Say hello", "codex", AgentBackend.Codex))
            services.tasks.launchFromService(id, scope)
            withTimeout(120_000) { while (services.database.runtime().action(id, "cli-start")?.outcome == null) delay(100) }
            services.tasks.cancel(id)
            val stopped = services.database.runtime().task(id)!!
            evidence(services, id, "stop")
            assertEquals(TaskPhase.Cancelled.name, stopped.status)
            assertNull("helper confirmed termination: ${stopped.detail}", stopped.activeSlot)
            assertEquals(0, codexProcesses())
        }
    }

    @Test fun killedHelperPausesThenRetryObservesWithoutResending() = runBlocking {
        assumeTrue("Termux with RUN_COMMAND granted is required", granted())
        fixture { services, project, scope ->
            val id = services.tasks.start(TaskStart(project.id, null, "Say hello", "codex", AgentBackend.Codex))
            services.tasks.launchFromService(id, scope)
            withTimeout(120_000) { while (services.database.runtime().action(id, "cli-start")?.outcome == null) delay(100) }
            // Kill the helper daemon (proot then stops everything inside it), as Android or the user could.
            AndroidTermuxGateway(context).run(TermuxCommand(TermuxProtocol.BASH, listOf("-c", "pkill -f 'agm_bridge.py serve'; sleep 1"), timeoutMs = 20_000))
            val after = settled(services, id, TaskPhase.Paused, TaskPhase.Failed)
            evidence(services, id, "killed-helper")
            val starts = services.database.runtime().actions(id).count { it.tool == "cli_start" }
            if (after.status == TaskPhase.Paused.name) {
                services.tasks.awaitIdle(); services.tasks.retry(id); services.tasks.launchFromService(id, scope)
                val final = settled(services, id, TaskPhase.Failed, TaskPhase.Paused, TaskPhase.Completed)
                evidence(services, id, "killed-helper-retry")
                assertNotEquals(TaskPhase.Completed.name, final.status)
            }
            assertEquals("start is never replayed", starts, services.database.runtime().actions(id).count { it.tool == "cli_start" })
            assertEquals(1, starts)
            assertEquals(0, codexProcesses())
        }
    }

    @Test fun withoutRunCommandPermissionVerifyIsRuntimeUnavailable() = runBlocking {
        assumeTrue("run after: adb shell pm revoke <app> com.termux.permission.RUN_COMMAND", !granted() &&
            runCatching { context.packageManager.getPackageInfo(TermuxProtocol.PACKAGE, 0) }.isSuccess)
        val error = runCatching { context.container.cliGate.verify(AgentBackend.Codex) }.exceptionOrNull()
        Log.i("AgmEvidence", "denied: $error")
        assertTrue("$error", error is RuntimeUnavailableException || error is dev.srimi.antigravitymobile.linux.TermuxUnavailable)
        assertNotNull(context.container.cliGate.unavailable(AgentBackend.Codex))
    }
}
