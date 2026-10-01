package dev.srimi.antigravitymobile

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.providers.*
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(id: String = UUID.randomUUID().toString()) = RuntimeTaskRecord(id, "p", "c", "CHATGPT", "Native",
        "build", TaskPhase.AwaitingApproval.name, "waiting", null, "[]", 1, "Tools", 0, null, false, 1, 1, 1)
    private fun action(task: RuntimeTaskRecord, category: ApprovalCategory = ApprovalCategory.Build) =
        RuntimeActionRecord(UUID.randomUUID().toString(), task.id, "call", "build_project", "{}", "build", "snapshot",
            category.name, UUID.randomUUID().toString(), "AWAITING_APPROVAL", null, null, null, null, 1, 1)
    private suspend fun database(block: suspend (SessionStore) -> Unit) {
        val store = Room.inMemoryDatabaseBuilder(context, SessionStore::class.java).build()
        try { block(store) } finally { store.close() }
    }

    @Test fun keyedApprovalAndExecutionClaimsAreAtomicAndFirstDecisionWins() = runBlocking {
        database { store ->
            val dao = store.runtime(); val task = task(); val action = action(task)
            dao.createTask(task); dao.createAction(action)
            suspend fun answer(key: ApprovalKey, allEdits: Boolean = false, decision: String = "APPROVED") =
                dao.answer(key.taskId, key.actionId, key.toolCallId, key.buildId, decision, allEdits, 2)
            val key = action.key()
            assertFalse(answer(key.copy(taskId = "stale-task")))
            assertFalse(answer(key.copy(actionId = "stale-action")))
            assertFalse(answer(key.copy(toolCallId = "stale-call")))
            assertFalse(answer(key.copy(buildId = "stale-build")))
            assertFalse(answer(key, allEdits = true))
            assertEquals(1, coroutineScope { (1..20).map { async(Dispatchers.IO) { answer(key) } }.awaitAll().count { it } })
            assertFalse(answer(key, decision = "DECLINED"))
            assertEquals("APPROVED", dao.action(task.id, "call")!!.decision)
            assertEquals(1, coroutineScope { (1..20).map { async(Dispatchers.IO) { dao.claim(action.id, 3) } }.awaitAll().sum() })
            val failure = ToolOutcome.Failed("actual worker error", "build-log")
            assertEquals(1, dao.finish(action.id, RuntimeCodec.outcome(failure), failure.text(), "FAILED", 4))
            assertEquals(0, dao.finish(action.id, RuntimeCodec.outcome(ToolOutcome.Success("wrong")), "wrong", "COMPLETED", 5))
            assertEquals("FAILED", dao.action(task.id, "call")!!.status)
        }
    }

    @Test fun approveAllEditsPersistsReceiptButCannotApproveBuildOrInstall() = runBlocking {
        database { store ->
            val dao = store.runtime(); val task = task(); val edit = action(task, ApprovalCategory.Edit)
            dao.createTask(task); dao.createAction(edit)
            assertTrue(dao.answer(task.id, edit.id, edit.toolCallId, edit.buildId, "APPROVED", true, 2))
            assertTrue(dao.task(task.id)!!.autoApproveEdits)
            assertTrue(dao.action(task.id, edit.toolCallId)!!.allEdits)
            for (category in listOf(ApprovalCategory.Build, ApprovalCategory.Install)) {
                val separate = action(task, category).copy(toolCallId = category.name)
                dao.createAction(separate)
                assertFalse(dao.answer(task.id, separate.id, separate.toolCallId, separate.buildId, "APPROVED", true, 3))
                assertEquals(0, dao.claim(separate.id, 4))
                assertNull(dao.action(task.id, separate.toolCallId)!!.decision)
            }
        }
    }

    @Test fun stopClosesApprovalAsCancellationAndRejectsLateTap() = runBlocking {
        database { store ->
            val dao = store.runtime(); val task = task(); val action = action(task)
            dao.createTask(task); dao.createAction(action)
            dao.phase(task.id, TaskPhase.Cancelled.name, "stopped", null, 1, 2)
            dao.closePending(task.id, "CANCELLED", 2)
            assertFalse(dao.answer(task.id, action.id, action.toolCallId, action.buildId, "DECLINED", false, 3))
            assertEquals("CANCELLED", dao.action(task.id, action.toolCallId)!!.decision)
            assertEquals(0, dao.claim(action.id, 4))
            dao.releaseCancelled(task.id)
            assertNull(dao.active())
        }
    }

    @Test fun migrationFromVersion3RetainsBuildsAndPersistsProviderUsageAndRuntimeReceipt() = runBlocking {
        val name = "runtime-upgrade-${UUID.randomUUID()}.db"
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile!!.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE checks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, startedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL)")
            SessionStore.MIGRATION_1_2_SQL.forEach(db::execSQL)
            db.execSQL("CREATE TABLE build_runs (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, tasks TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, snapshotHash TEXT NOT NULL, createdAt INTEGER NOT NULL, finishedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL)")
            db.execSQL("INSERT INTO build_runs VALUES ('old-build', 'p', 'help', 'FAILED', 'kept log detail', 'hash', 1, 2, 3)")
            db.version = 3
        }
        fun open() = Room.databaseBuilder(context, SessionStore::class.java, name).addMigrations(SessionStore.MIGRATION_3_4).build()
        var store = open()
        try {
            assertEquals("kept log detail", store.builds().find("old-build")!!.detail)
            val task = task(); val action = action(task, ApprovalCategory.Edit)
            store.runtime().createTask(task); store.runtime().createAction(action)
            assertTrue(store.runtime().answer(task.id, action.id, action.toolCallId, action.buildId, "APPROVED", true, 2))
            val usage = RoomProviderUsageStore(store.providerUsage())
            usage.recordModel(ModelVerification("provider", "model", true, 10))
            usage.record(UsageRecord("provider", "model", task.id, null, null, 1, 10))
            assertNull(usage.totals("provider", "model", 0).inputTokens)
            usage.record(UsageRecord("provider", "model", task.id, 5_000_000_000, 8, 2, 11))
            store.close(); store = open()
            val restoredUsage = RoomProviderUsageStore(store.providerUsage())
            assertEquals(UsageTotals(3, 5_000_000_000, 8, 0), restoredUsage.totals("provider", "model", 0))
            assertTrue(restoredUsage.models("provider").single().toolCallingVerified)
            assertTrue(store.runtime().action(task.id, "call")!!.allEdits)
            assertFalse(store.runtime().answer(task.id, action.id, "call", action.buildId, "DECLINED", false, 3))
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
