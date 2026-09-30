package dev.srimi.antigravitymobile

import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
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
        val coordinator = BuildCoordinator(context,store.builds(),projects,scope)
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
