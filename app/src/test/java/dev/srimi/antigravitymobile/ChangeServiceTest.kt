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
}
