package dev.srimi.antigravitymobile

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FullAppDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scratch() = File(context.cacheDir, "device-test-${UUID.randomUUID()}").apply { mkdirs() }

    @Test fun migrationFromVersion2PreservesProjectAndConversation() = runBlocking {
        val name = "migration-v2-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE `checks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            SessionStore.MIGRATION_1_2_SQL.forEach(db::execSQL)
            db.execSQL("INSERT INTO projects VALUES ('p','kept project','dir',1,2)")
            db.execSQL("INSERT INTO conversations VALUES ('c','p','kept chat','chatgpt','READY',1,2)")
            db.execSQL("INSERT INTO messages VALUES ('m','c','user','kept message',1)")
            db.version = 2
        }
        val store=Room.databaseBuilder(context,SessionStore::class.java,name).addMigrations(SessionStore.MIGRATION_2_3, SessionStore.MIGRATION_3_4).build()
        try {
            assertEquals("kept project",store.projects().find("p")!!.name)
            val messages=store.conversations().messages("c")
            assertEquals("kept message",messages.single().content)
            val build=BuildRecord(UUID.randomUUID().toString(),"p","help","AWAITING_APPROVAL","prepared","hash",1)
            store.builds().save(build)
            assertEquals(1,store.builds().claimApproval(build.id))
            assertEquals(0,store.builds().claimApproval(build.id))
            assertEquals("DISPATCHING",store.builds().find(build.id)!!.status)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun migrationFromVersion1KeepsCheckHistory() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `checks` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO checks VALUES ('old', 'Native command: version', 'PASSED', 'kept', 1, 2)")
            db.version = 1
        }
        val store = Room.databaseBuilder(context, SessionStore::class.java, name).addMigrations(SessionStore.MIGRATION_1_2, SessionStore.MIGRATION_2_3, SessionStore.MIGRATION_3_4).build()
        try {
            assertEquals("kept", store.checks().all().single().detail)
            store.projects().save(ProjectRecord("p", "Project", "dir", 1, 1))
            store.changes().saveSet(ChangeSetRecord("s", "p", null, "summary", "REVIEW", "", 1, 1))
            store.changes().saveFile(ChangeFileRecord("s", "a.kt", true, true, 1))
            assertEquals("Project", store.projects().find("p")!!.name)
            assertEquals(1, store.changes().files("s").size)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun jgitInitCommitStatusAndLogRunOnDevice() {
        val base = scratch()
        try {
            val git = GitService(File(context.noBackupFilesDir, "git-home"))
            val repo = File(base, "repo").apply { mkdirs() }
            git.init(repo)
            File(repo, "Main.kt").writeText("fun main() {}\n")
            val id = git.commit(repo, "First", PersonIdent("Device Test", "device@example.invalid"))
            assertTrue(git.status(repo).clean)
            assertEquals(id, git.log(repo).single().id)
        } finally { Archives.deleteTree(base) }
    }

    @Test fun changeLedgerRevertsThroughRoomAndRefusesLaterEdit() = runBlocking {
        val base = scratch()
        val store = Room.inMemoryDatabaseBuilder(context, SessionStore::class.java).build()
        try {
            val workspace = WorkspaceService(File(base, "project"), File(base, "checkpoints"))
            val changes = ChangeService(store.changes(), File(base, "snapshots"))
            workspace.write("A.kt", "before")
            val first = changes.open("p", null, "one")
            changes.apply(first.id, workspace, "A.kt", "agent".toByteArray())
            changes.finish(first.id)
            changes.revert(first.id, workspace)
            assertEquals("before", workspace.read("A.kt"))
            val second = changes.open("p", null, "two")
            changes.apply(second.id, workspace, "A.kt", "agent".toByteArray())
            changes.finish(second.id)
            workspace.write("A.kt", "user")
            assertThrows(IllegalStateException::class.java) { runBlocking { changes.revert(second.id, workspace) } }
            assertEquals("user", workspace.read("A.kt"))
        } finally { store.close(); Archives.deleteTree(base) }
    }

    @Test fun composeTemplateExtractsIntoProjectWithExecutableWrapper() {
        val base = scratch()
        try {
            context.assets.open("hello-phone.zip").use { Archives.extract(it, base) }
            assertTrue(File(base, "gradlew").canExecute())
            val report = BuildInspector.inspect(base)
            assertTrue(report.gradleProject && report.wrapper && report.androidApp)
            assertEquals(CheckStatus.PASSED, report.status)
        } finally { Archives.deleteTree(base) }
    }
}
