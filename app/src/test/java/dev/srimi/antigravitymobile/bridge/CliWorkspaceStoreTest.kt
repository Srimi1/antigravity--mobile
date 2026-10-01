package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.WorkspaceService
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CliWorkspaceStoreTest {
    private fun archive(file: File, vararg entries: Pair<String, String>): File = file.also {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, value) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
    }
    @Test fun sourceExcludesAccountsGitKeysAndOutputsAndBaselineSurvivesReopen() {
        val root = Files.createTempDirectory("cli-source").toFile()
        try {
            val project = File(root, "project").apply { mkdirs() }
            listOf("src/Game.kt", ".git/config", ".codex/auth.json", ".agents/mcp_config.json", ".signing/personal.p12", ".env", "app/build/out.txt", "local.properties").forEach {
                File(project, it).apply { parentFile!!.mkdirs(); writeText("fixture") }
            }
            val store = CliWorkspaceStore(File(root, "private"))
            val saved = store.create("task-1", "project-1", project)
            assertEquals(setOf("src/Game.kt"), saved.hashes.keys)
            assertEquals(saved, CliWorkspaceStore(File(root, "private")).recorded("task-1"))
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { store.create("task-1", "project-1", project) }
        } finally { root.deleteRecursively() }
    }
    @Test fun returnedChangesStayPrivateAndLaterOwnerEditRefusesWholeImport() {
        val root = Files.createTempDirectory("cli-diff").toFile()
        try {
            val workspace = WorkspaceService(File(root, "project"), File(root, "checkpoints"))
            workspace.write("src/Game.kt", "old"); workspace.write("old.txt", "remove")
            val store = CliWorkspaceStore(File(root, "private"))
            store.create("task-1", "project-1", workspace.rootDirectory)
            val diffs = store.differences("task-1", archive(File(root, "result.zip"), "src/Game.kt" to "new", "new.txt" to "added"))
            assertEquals(setOf("src/Game.kt", "old.txt", "new.txt"), diffs.map { it.path }.toSet())
            assertEquals("old", workspace.read("src/Game.kt")); assertFalse(workspace.exists("new.txt"))
            store.checkConflicts(workspace, diffs)
            workspace.write("src/Game.kt", "owner edit")
            assertThrows(IllegalStateException::class.java) { store.checkConflicts(workspace, diffs) }
            assertEquals("owner edit", workspace.read("src/Game.kt")); assertTrue(workspace.exists("old.txt"))
        } finally { root.deleteRecursively() }
    }
    @Test fun traversalSecretsOversizeAndLinksAreRejected() {
        val root = Files.createTempDirectory("cli-bounds").toFile()
        try {
            val project = File(root, "project").apply { mkdirs() }
            File(project, "ok.txt").writeText("ok")
            val store = CliWorkspaceStore(File(root, "private"), CliWorkspaceStore.Limits(fileBytes = 4, totalBytes = 8))
            store.create("task-1", "project-1", project)
            listOf("../outside", ".git/config", ".codex/auth.json", "nested\\bad").forEach { path ->
                assertThrows(IllegalStateException::class.java) { store.differences("task-1", archive(File(root, "bad.zip"), path to "x")) }
            }
            assertThrows(IllegalStateException::class.java) { store.differences("task-1", archive(File(root, "big.zip"), "big.txt" to "12345")) }
            Files.createSymbolicLink(File(project, "linked.txt").toPath(), File(root, "outside").apply { writeText("secret fixture") }.toPath())
            assertThrows(IllegalStateException::class.java) { store.create("task-2", "project-1", project) }
            assertFalse(File(root, "private/task-2").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptBaselineCannotBecomeAnImport() {
        val root = Files.createTempDirectory("cli-baseline").toFile()
        try {
            val project = File(root, "project").apply { mkdirs() }
            File(project, "Game.kt").writeText("before")
            val store = CliWorkspaceStore(File(root, "private"))
            store.create("task-1", "project-1", project)
            File(root, "private/task-1/before/Game.kt").writeText("changed")
            assertThrows(IllegalStateException::class.java) { store.differences("task-1", archive(File(root, "result.zip"), "Game.kt" to "after")) }
        } finally { root.deleteRecursively() }
    }
    @Test fun malformedArchiveCannotBeMisreadAsDeleteAll() {
        val root = Files.createTempDirectory("cli-malformed").toFile()
        try {
            val project = File(root, "project").apply { mkdirs() }
            File(project, "Game.kt").writeText("before")
            val store = CliWorkspaceStore(File(root, "private"))
            store.create("task-1", "project-1", project)
            val bad = File(root, "bad.zip").apply { writeText("invalid archive") }
            assertThrows(java.util.zip.ZipException::class.java) { store.differences("task-1", bad) }
            assertEquals("before", File(project, "Game.kt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun concurrentCreateReservesOneImmutableSnapshotAndLosersCannotDeleteIt() {
        val root = Files.createTempDirectory("cli-reservation").toFile()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val project = File(root, "project").apply { mkdirs() }
            File(project, "Game.kt").writeText("source")
            val store = CliWorkspaceStore(File(root, "private"))
            val jobs = (1..16).map { pool.submit<Boolean> {
                try { store.create("task-1", "project-1", project); true }
                catch (_: java.nio.file.FileAlreadyExistsException) { false }
            } }
            assertEquals(1, jobs.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(setOf("Game.kt"), store.recorded("task-1").hashes.keys)
        } finally { pool.shutdownNow(); root.deleteRecursively() }
    }
}
