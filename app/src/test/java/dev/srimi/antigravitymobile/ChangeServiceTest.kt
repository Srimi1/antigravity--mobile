package dev.srimi.antigravitymobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** In-memory stand-in for the Room DAO so the ledger logic runs on the JVM. */
class MemoryChangeDao : ChangeDao {
    val sets = linkedMapOf<String, ChangeSetRecord>()
    val files = linkedMapOf<Pair<String, String>, ChangeFileRecord>()
    override suspend fun saveSet(set: ChangeSetRecord) { sets[set.id] = set }
    override suspend fun saveFile(file: ChangeFileRecord) { files[file.changeSetId to file.path] = file }
    override suspend fun findSet(id: String) = sets[id]
    override fun observeSets(projectId: String): Flow<List<ChangeSetRecord>> = flowOf(sets.values.filter { it.projectId == projectId })
    override suspend fun sets(projectId: String) = sets.values.filter { it.projectId == projectId }
    override suspend fun setsWithStatus(status: String) = sets.values.filter { it.status == status }
    override suspend fun files(changeSetId: String) = files.values.filter { it.changeSetId == changeSetId }.sortedBy { it.path }
    override suspend fun file(changeSetId: String, path: String) = files[changeSetId to path]
    override suspend fun deleteFiles(changeSetId: String) { files.keys.removeAll { it.first == changeSetId } }
    override suspend fun deleteSet(id: String) { sets.remove(id) }
}

class ChangeServiceTest {
    private val base = Files.createTempDirectory("changes").toFile()
    private val workspace = WorkspaceService(File(base, "project"), File(base, "checkpoints"))
    private val dao = MemoryChangeDao()
    private val changes = ChangeService(dao, File(base, "snapshots"))

    @Test fun recordsEditsCreationAndDeletionThenRevertsAll() = runBlocking {
        workspace.write("src/A.kt", "val a = 1\n")
        workspace.write("old.txt", "remove me")
        workspace.write("notes.txt", "unrelated")
        val set = changes.open("p", "c", "Refactor")
        changes.apply(set.id, workspace, "src/A.kt", "val a = 2\n".toByteArray())
        changes.apply(set.id, workspace, "src/A.kt", "val a = 3\n".toByteArray())
        changes.apply(set.id, workspace, "src/B.kt", "val b = 1\n".toByteArray())
        changes.apply(set.id, workspace, "old.txt", null)
        changes.finish(set.id)
        assertEquals("REVIEW", dao.findSet(set.id)!!.status)
        val diffs = changes.diffs(set.id).associateBy { it.path }
        assertEquals(setOf("src/A.kt", "src/B.kt", "old.txt"), diffs.keys)
        assertEquals("val a = 1\n", String(diffs.getValue("src/A.kt").before!!))
        assertEquals("val a = 3\n", String(diffs.getValue("src/A.kt").after!!))
        assertEquals("added", diffs.getValue("src/B.kt").kind)
        assertEquals("deleted", diffs.getValue("old.txt").kind)
        changes.revert(set.id, workspace)
        assertEquals("val a = 1\n", workspace.read("src/A.kt"))
        assertFalse(workspace.exists("src/B.kt"))
        assertEquals("remove me", workspace.read("old.txt"))
        assertEquals("unrelated", workspace.read("notes.txt"))
        assertEquals("REVERTED", dao.findSet(set.id)!!.status)
    }

    @Test fun refusesRevertAfterLaterUserEdit() = runBlocking {
        workspace.write("A.kt", "before")
        val set = changes.open("p", null, "Edit")
        changes.apply(set.id, workspace, "A.kt", "agent".toByteArray())
        changes.finish(set.id)
        workspace.write("A.kt", "user edit")
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { changes.revert(set.id, workspace) } }
        assertTrue(error.message!!.contains("Later edit"))
        assertEquals("user edit", workspace.read("A.kt"))
        assertEquals("REVIEW", dao.findSet(set.id)!!.status)
    }

    @Test fun noOpTaskIsDiscardedAndInterruptedSetBecomesReviewable() = runBlocking {
        workspace.write("A.kt", "same")
        val empty = changes.open("p", null, "Nothing")
        changes.apply(empty.id, workspace, "A.kt", "same".toByteArray())
        changes.finish(empty.id)
        assertNull(dao.findSet(empty.id))
        val open = changes.open("p", null, "Killed")
        changes.apply(open.id, workspace, "A.kt", "changed".toByteArray())
        changes.recoverInterrupted()
        assertEquals("REVIEW", dao.findSet(open.id)!!.status)
        changes.accept(open.id)
        assertEquals("ACCEPTED", dao.findSet(open.id)!!.status)
        changes.markCommitted(listOf(open.id), "abcdef1234567")
        assertEquals("COMMITTED", dao.findSet(open.id)!!.status)
    }

    @Test fun rejectsTraversalAndClosedSets() = runBlocking {
        val set = changes.open("p", null, "Edit")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { changes.apply(set.id, workspace, "../escape.kt", "x".toByteArray()) }
        }
        changes.apply(set.id, workspace, "A.kt", "x".toByteArray())
        changes.finish(set.id)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { changes.apply(set.id, workspace, "A.kt", "y".toByteArray()) }
        }
        assertFalse(File(base, "escape.kt").exists())
    }

    @Test fun snapshotOfDotTmpSiblingSurvivesEditOfItsNamesake() = runBlocking {
        workspace.write(".A.kt.tmp", "original-temporary-name")
        workspace.write("A.kt", "original-A")
        val set = changes.open("p", "c", "Collision")
        changes.apply(set.id, workspace, ".A.kt.tmp", "edited-temporary-name".toByteArray())
        changes.apply(set.id, workspace, "A.kt", "edited-A".toByteArray())
        val diffs = changes.diffs(set.id).associateBy { it.path }
        assertEquals("original-temporary-name", String(diffs.getValue(".A.kt.tmp").before!!))
        assertEquals("original-A", String(diffs.getValue("A.kt").before!!))
        changes.finish(set.id)
        changes.revert(set.id, workspace)
        assertEquals("original-temporary-name", workspace.read(".A.kt.tmp"))
        assertEquals("original-A", workspace.read("A.kt"))
    }

    @Test fun revertIsRefusedForAnotherProject() = runBlocking {
        workspace.write("A.kt", "one")
        val set = changes.open("p", "c", "Edit")
        changes.apply(set.id, workspace, "A.kt", "two".toByteArray())
        changes.finish(set.id)
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { changes.revert(set.id, workspace, "other-project") } }
        assertTrue(error.message!!.contains("another project"))
        assertEquals("two", workspace.read("A.kt"))
        changes.revert(set.id, workspace, "p")
        assertEquals("one", workspace.read("A.kt"))
    }

    @Test fun revertRetryAcceptsFilesAlreadyRestored() = runBlocking {
        workspace.write("A.kt", "a1"); workspace.write("B.kt", "b1")
        val set = changes.open("p", "c", "Edit")
        changes.apply(set.id, workspace, "A.kt", "a2".toByteArray())
        changes.apply(set.id, workspace, "B.kt", "b2".toByteArray())
        changes.finish(set.id)
        workspace.write("A.kt", "a1") // as left by an earlier, partly completed revert
        changes.revert(set.id, workspace, "p")
        assertEquals("a1", workspace.read("A.kt")); assertEquals("b1", workspace.read("B.kt"))
        assertEquals("REVERTED", dao.sets.getValue(set.id).status)
    }

    /** DAO whose [failAt]-th saveFile fails, simulating process death at that point. */
    private class FailingDao(val inner: MemoryChangeDao = MemoryChangeDao(), var failAt: Int = 0) : ChangeDao by inner {
        private var saves = 0
        override suspend fun saveFile(file: ChangeFileRecord) {
            if (++saves == failAt) throw IllegalStateException("simulated crash")
            inner.saveFile(file)
        }
    }

    @Test fun interruptedWriteIsRecoveredFromTheWorkspaceNotLost() = runBlocking {
        // B.kt: saves 1 (provisional) and 2 (after). A.kt: save 3 provisional, save 4 after the workspace write fails.
        val failing = FailingDao(failAt = 4)
        val ledger = ChangeService(failing, File(base, "snapshots2"), workspaceFor = { workspace })
        workspace.write("A.kt", "old")
        val set = ledger.open("p", "c", "Edit")
        ledger.apply(set.id, workspace, "B.kt", "first".toByteArray())
        assertThrows(IllegalStateException::class.java) { runBlocking { ledger.apply(set.id, workspace, "A.kt", "new".toByteArray()) } }
        assertEquals("new", workspace.read("A.kt")) // the workspace write happened
        ledger.recoverInterrupted()
        val diff = ledger.diffs(set.id).associateBy { it.path }
        assertEquals("old", String(diff.getValue("A.kt").before!!))
        assertEquals("new", String(diff.getValue("A.kt").after!!))
        assertEquals("REVIEW", failing.inner.sets.getValue(set.id).status)
        ledger.revert(set.id, workspace, "p")
        assertEquals("old", workspace.read("A.kt"))
        assertFalse(workspace.exists("B.kt"))
    }

    @Test fun finishDoesNotDiscardAnInterruptedOnlyEdit() = runBlocking {
        val failing = FailingDao(failAt = 2)
        val ledger = ChangeService(failing, File(base, "snapshots3"), workspaceFor = { workspace })
        workspace.write("A.kt", "old")
        val set = ledger.open("p", "c", "Edit")
        assertThrows(IllegalStateException::class.java) { runBlocking { ledger.apply(set.id, workspace, "A.kt", "new".toByteArray()) } }
        ledger.finish(set.id)
        assertEquals("REVIEW", failing.inner.sets.getValue(set.id).status)
        assertEquals("new", String(ledger.diffs(set.id).single().after!!))
    }

    @Test fun conflictBeforeWriteLeavesNoPendingState() = runBlocking {
        workspace.write("A.kt", "old")
        val set = changes.open("p", "c", "Edit")
        val stale = FileBaseline("something else".toByteArray())
        assertThrows(IllegalStateException::class.java) { runBlocking { changes.apply(set.id, workspace, "A.kt", "new".toByteArray(), stale) } }
        changes.finish(set.id)
        assertNull(dao.sets[set.id]) // nothing changed, so the set is discarded
    }

    @Test fun interruptedWriteWithoutWorkspaceStaysReviewable() = runBlocking {
        val failing = FailingDao(failAt = 2)
        val ledger = ChangeService(failing, File(base, "snapshots4"))
        workspace.write("A.kt", "old")
        val set = ledger.open("p", "c", "Edit")
        assertThrows(IllegalStateException::class.java) { runBlocking { ledger.apply(set.id, workspace, "A.kt", "new".toByteArray()) } }
        ledger.recoverInterrupted()
        val stored = failing.inner.sets.getValue(set.id)
        assertEquals("REVIEW", stored.status)
        assertTrue(stored.detail, stored.detail.contains("A.kt"))
    }

    @Test fun reviewedFinalTakesTheLatestAcceptedResultPerPath() = runBlocking {
        var now = 1L
        val ledger = ChangeService(dao, File(base, "snapshots5"), clock = { now++ })
        workspace.write("A.kt", "a0"); workspace.write("B.kt", "b0")
        val first = ledger.open("p", "c", "First")
        ledger.apply(first.id, workspace, "A.kt", "a1".toByteArray())
        ledger.apply(first.id, workspace, "B.kt", null)
        ledger.finish(first.id)
        val second = ledger.open("p", "c", "Second")
        ledger.apply(second.id, workspace, "A.kt", "a2".toByteArray())
        ledger.finish(second.id)
        val reviewed = ledger.reviewedFinal(listOf(second.id, first.id))
        assertEquals("a2", String(reviewed.getValue("A.kt")!!))
        assertTrue(reviewed.containsKey("B.kt")); assertNull(reviewed["B.kt"])
    }
}
