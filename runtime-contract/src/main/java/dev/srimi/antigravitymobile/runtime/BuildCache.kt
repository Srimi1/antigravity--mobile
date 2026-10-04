package dev.srimi.antigravitymobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Gradle user-home selection for the build worker.
 *
 * A project keeps one dependency cache across builds, so downloads and artifact
 * transforms are not repeated. Caches are never shared between projects: a
 * build script can alter its own cache, and it must not poison another project's.
 * A cache whose last build did not end with a Gradle exit code (cancel, worker
 * death, timeout) is wiped before reuse instead of trusted.
 */
object BuildCache {
    const val MAX_PROJECT_CACHES = 3
    private const val PROJECTS = "projects"
    private const val BUSY = ".agm-building"

    fun key(projectId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun validateKey(key: String) {
        require(key.matches(Regex("[a-f0-9]{32}"))) { "Invalid cache key" }
    }

    class Slot internal constructor(val directory: File, val shared: Boolean, val reused: Boolean)

    /** Prepare the Gradle user home for one build. Callers allow one build at a time. */
    fun acquire(root: File, key: String?, buildId: String, now: Long = System.currentTimeMillis()): Slot {
        root.mkdirs()
        // Earlier tools kept one never-deleted cache directory per build ID.
        root.listFiles().orEmpty().filter { it.name != PROJECTS }.forEach { deleteTree(it) }
        if (key == null) {
            val directory = File(root, buildId)
            directory.mkdirs()
            return Slot(directory, shared = false, reused = false)
        }
        validateKey(key)
        val projects = File(root, PROJECTS).apply { mkdirs() }
        val directory = File(projects, key)
        var reused = directory.isDirectory
        if (reused && File(directory, BUSY).exists()) { deleteTree(directory); reused = false }
        directory.mkdirs()
        reused = reused && directory.list().orEmpty().isNotEmpty()
        File(directory, BUSY).writeText(now.toString())
        directory.setLastModified(now)
        projects.listFiles().orEmpty().filter { it.isDirectory && it.name != key }
            .sortedByDescending { it.lastModified() }.drop(MAX_PROJECT_CACHES - 1).forEach { deleteTree(it) }
        return Slot(directory, shared = true, reused = reused)
    }

    /** [healthy] means Gradle itself ran to an exit code, so the cache is consistent. */
    fun release(slot: Slot, healthy: Boolean, now: Long = System.currentTimeMillis()) {
        if (!slot.shared) { deleteTree(slot.directory); return }
        if (healthy) {
            File(slot.directory, BUSY).delete()
            slot.directory.setLastModified(now)
        }
    }

    fun sizeBytes(root: File): Long {
        var total = 0L
        if (!root.exists()) return 0
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    /** Removes a tree without following links. */
    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
