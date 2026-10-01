package dev.srimi.antigravitymobile

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BuildWorkerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun awaitStatus(coordinator: BuildCoordinator, id: String, status: String) {
        withTimeout(180000) { while (coordinator.client.status(id).optString("status") != status) delay(500) }
    }
    private suspend fun fixture(block: suspend (BuildCoordinator, ProjectRepository, SessionStore) -> Unit) {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        val store = Room.inMemoryDatabaseBuilder(context,SessionStore::class.java).build()
        val base = File(context.cacheDir,"worker-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val projects = ProjectRepository(store.projects(),base)
        val coordinator = BuildCoordinator(context,store.builds(),projects,scope,store.runtime())
        try {
            assertTrue("Install the matching worker APK on the QA emulator first",coordinator.client.installed())
            assertNotEquals(context.applicationInfo.uid,context.packageManager.getApplicationInfo(P.WORKER,0).uid)
            block(coordinator,projects,store)
        } finally { scope.cancel(); store.close(); Archives.deleteTree(base) }
    }
    @Test fun approvalUsesImmutableSnapshotAndWorkerCannotReadMainPrivateFile() = runBlocking {
        fixture { builds,projects,store ->
            val privateFile = File(context.filesDir,"isolation-${UUID.randomUUID()}").apply { writeText("test marker") }
            try {
                val record = ProjectRecord(UUID.randomUUID().toString(),"Worker proof","proof",1,1)
                store.projects().save(record)
                val project = projects.directory(record).apply { mkdirs() }
                File(project,"settings.gradle").writeText("rootProject.name='proof'\n")
                val script = File(project,"build.gradle").apply { writeText("""
                    tasks.register('proof') { doLast {
                        assert !new File('${privateFile.path}').canRead()
                        println 'APPROVED_SNAPSHOT_ISOLATED'
                    } }
                """.trimIndent()) }
                val declined = builds.prepare(record,"proof")
                builds.decline(declined.id)
                assertEquals("NOT_FOUND",builds.client.status(declined.id).getString("status"))
                val approved = builds.prepare(record,"proof")
                script.writeText("throw new GradleException('ORIGINAL_CHANGED_AFTER_SNAPSHOT')")
                builds.approve(approved.id)
                awaitStatus(builds,approved.id,"COMPLETED")
                withTimeout(10000) { while(store.builds().find(approved.id)?.status!="COMPLETED") delay(200) }
                assertTrue(builds.client.status(approved.id).getString("output").contains("APPROVED_SNAPSHOT_ISOLATED"))
                assertThrows(IllegalStateException::class.java) { runBlocking { builds.approve(approved.id) } }
                assertEquals("test marker",privateFile.readText())
            } finally { privateFile.delete() }
        }
    }
    @Test fun cancellingForegroundBuildTerminatesItsTask() = runBlocking {
        fixture { builds,projects,store ->
            val record=ProjectRecord(UUID.randomUUID().toString(),"Worker cancel","cancel",1,1)
            store.projects().save(record)
            val project=projects.directory(record).apply { mkdirs() }
            File(project,"settings.gradle").writeText("rootProject.name='cancel'\n")
            File(project,"build.gradle").writeText("tasks.register('waitProof') { doLast { println 'WAIT_PROOF_STARTED'; Thread.sleep(120000) } }")
            val approved=builds.prepare(record,"waitProof")
            try {
                builds.approve(approved.id)
                withTimeout(180000) { while(!builds.client.status(approved.id).optString("output").contains("WAIT_PROOF_STARTED")) delay(500) }
                builds.cancel(approved.id)
                awaitStatus(builds,approved.id,"CANCELLED")
                assertEquals("CANCELLED",builds.client.status(approved.id).getString("status"))
            } finally { runCatching { builds.cancel(approved.id) } }
        }
    }

    @Test fun agentApprovalHasOneWorkerRunAndBuildTabCannotStartPendingAgentBuild() = runBlocking {
        fixture { builds, projects, store ->
            val project = ProjectRecord(UUID.randomUUID().toString(), "Agent approval proof", "agent-proof", 1, 1)
            store.projects().save(project)
            val directory = projects.directory(project).apply { mkdirs() }
            File(directory, "settings.gradle").writeText("rootProject.name='agent-proof'\n")
            File(directory, "build.gradle").writeText("tasks.register('proof') { doLast { println 'ONE_AGENT_APPROVAL_ONE_BUILD' } }")
            val taskId = UUID.randomUUID().toString()
            val task = RuntimeTaskRecord(taskId, project.id, "fixture-chat", "CHATGPT", "Native", "proof",
                TaskPhase.AwaitingApproval.name, "waiting", null, "[]", 1, "Tools", 0, null, false, 1, 1, 1)
            store.runtime().createTask(task)
            val prepared = builds.prepare(project, "proof", taskId)
            assertThrows(IllegalStateException::class.java) { runBlocking { builds.approve(prepared.id) } }
            assertThrows(IllegalStateException::class.java) { runBlocking { builds.decline(prepared.id) } }
            assertEquals("NOT_FOUND", builds.client.status(prepared.id).getString("status"))
            val action = RuntimeActionRecord(UUID.randomUUID().toString(), taskId, "build-call", "build_project", "{}",
                "proof", "snapshot", ApprovalCategory.Build.name, prepared.id, "AWAITING_APPROVAL", null, null, null, null, 1, 1)
            store.runtime().createAction(action)
            assertTrue(store.runtime().answer(taskId, action.id, action.toolCallId, prepared.id, "APPROVED", false, 2))
            assertFalse(store.runtime().answer(taskId, action.id, action.toolCallId, prepared.id, "APPROVED", false, 3))
            assertEquals(1, store.runtime().claim(action.id, 4))
            builds.approve(prepared.id, taskId)
            awaitStatus(builds, prepared.id, "COMPLETED")
            assertThrows(IllegalStateException::class.java) { runBlocking { builds.approve(prepared.id, taskId) } }
            val status = builds.client.status(prepared.id)
            assertEquals(1, status.getString("output").split("ONE_AGENT_APPROVAL_ONE_BUILD").size - 1)
        }
    }

    @Test fun workerDeathIsInterruptedAndAnOldBuildIdCannotRunAgain() = runBlocking {
        fixture { builds,projects,store ->
            val record=ProjectRecord(UUID.randomUUID().toString(),"Worker recovery","recovery",1,1)
            store.projects().save(record)
            val project=projects.directory(record).apply { mkdirs() }
            File(project,"settings.gradle").writeText("rootProject.name='recovery'\n")
            File(project,"build.gradle").writeText("tasks.register('waitRecovery') { doLast { println 'WAIT_RECOVERY_STARTED'; Thread.sleep(120000) } }")
            val approved=builds.prepare(record,"waitRecovery")
            try {
                builds.approve(approved.id)
                withTimeout(180000) { while(!builds.client.status(approved.id).optString("output").contains("WAIT_RECOVERY_STARTED")) delay(500) }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop ${P.WORKER}")
                ).use { it.readBytes() }
                // Query reconnects to a new worker process; it only recovers the ledger.
                awaitStatus(builds,approved.id,"INTERRUPTED")
                withTimeout(10000) { while(store.builds().find(approved.id)?.status!="INTERRUPTED") delay(200) }
                assertThrows(IllegalStateException::class.java) { runBlocking {
                    builds.client.start(approved.id,listOf("waitRecovery"),builds.archive(approved.id),approved.snapshotHash)
                } }
                val status=builds.client.status(approved.id)
                assertEquals("INTERRUPTED",status.getString("status"))
                assertEquals(1,status.getString("output").split("WAIT_RECOVERY_STARTED").size-1)
            } finally { runCatching { builds.cancel(approved.id) } }
        }
    }
}
