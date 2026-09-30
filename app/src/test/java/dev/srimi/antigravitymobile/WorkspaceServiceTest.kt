package dev.srimi.antigravitymobile

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspaceServiceTest {
    private fun fixture(): Pair<WorkspaceService, java.io.File> {
        val base = Files.createTempDirectory("workspace-check").toFile()
        val root = java.io.File(base, "workspace")
        return WorkspaceService(root, java.io.File(base, "checkpoints")) to root
    }
    @Test fun rejectsTraversalAndAbsolutePaths() {
        val (service, _) = fixture()
        listOf("../outside", "/tmp/outside", "nested/../../outside", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { service.write(it, "bad") }
        }
    }
    @Test fun rejectsSymlinkEscape() {
        val (service, root) = fixture()
        val outside = Files.createTempDirectory("outside-check")
        Files.createSymbolicLink(root.toPath().resolve("escape"), outside)
        assertThrows(IllegalArgumentException::class.java) { service.write("escape/file", "bad") }
        assertFalse(outside.resolve("file").toFile().exists())
    }
    @Test fun rejectsTraversalThatReentersWorkspaceBeforeCheckpointCopy() {
        val (service, root) = fixture()
        service.write("source.kt", "original")
        val sentinel = java.io.File(root.parentFile, "checkpoints/workspace/source.kt")
        sentinel.parentFile!!.mkdirs()
        sentinel.writeText("unrelated checkpoint")
        assertThrows(IllegalArgumentException::class.java) {
            service.checkpoint(listOf("../workspace/source.kt"))
        }
        assertEquals("unrelated checkpoint", sentinel.readText())
    }
    @Test fun rollbackPreservesUnrelatedWorkAndRemovesCreatedFile() {
        val (service, _) = fixture()
        service.write("source.kt", "original")
        service.write("notes.txt", "my note")
        val checkpoint = service.checkpoint(listOf("source.kt", "new.kt"))
        service.write("source.kt", "agent edit")
        service.write("new.kt", "created")
        val changes = service.diff(checkpoint)
        assertEquals(2, changes.size)
        service.restore(changes)
        assertEquals("original", service.read("source.kt"))
        assertEquals("my note", service.read("notes.txt"))
        assertFalse("new.kt" in service.list())
    }
    @Test fun rejectsRollbackAfterLaterUserEdit() {
        val (service, _) = fixture()
        service.write("source.kt", "before")
        val checkpoint = service.checkpoint(listOf("source.kt"))
        service.write("source.kt", "agent")
        val changes = service.diff(checkpoint)
        service.write("source.kt", "later user edit")
        assertThrows(IllegalStateException::class.java) { service.restore(changes) }
        assertEquals("later user edit", service.read("source.kt"))
    }
}
