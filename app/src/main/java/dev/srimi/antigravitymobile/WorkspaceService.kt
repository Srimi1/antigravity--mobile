package dev.srimi.antigravitymobile

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FileBaseline(val bytes: ByteArray?)

/** Owns one app-private workspace. This is not a shell sandbox. */
class WorkspaceService(private val root: File, private val checkpointRoot: File) {
    init { root.mkdirs(); checkpointRoot.mkdirs() }
    val rootDirectory: File get() = root
    private fun resolve(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Use a relative workspace path" }
        require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Use a normalized workspace path" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Path escapes workspace" }
        return file
    }
    /** Validates a relative path and returns it unchanged. */
    fun normalize(path: String): String { resolve(path); return path }
    /** Project-relative path of the real target after following symlinks, for protected-path policy. */
    fun resolvedPath(path: String): String = resolve(path).relativeTo(root.canonicalFile).invariantSeparatorsPath
    fun exists(path: String): Boolean = resolve(path).exists()
    fun isDirectory(path: String): Boolean = resolve(path).isDirectory
    fun size(path: String): Long = resolve(path).length()
    fun read(path: String): String = resolve(path).readText()
    fun readBytes(path: String): ByteArray = resolve(path).readBytes()
    fun write(path: String, text: String) = writeBytes(path, text.toByteArray())
    private fun fileLock(path: String) = fileLocks.getOrPut(resolve(path).path) { Any() }
    fun writeBytes(path: String, bytes: ByteArray) = synchronized(fileLock(path)) {
        val file = resolve(path)
        file.parentFile!!.mkdirs()
        // Resolve again after parent creation, preserving the symlink boundary check.
        val target = resolve(path)
        val temporary = File.createTempFile(".agm-write-", ".tmp", target.parentFile)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            if (target.canExecute()) temporary.setExecutable(true, false)
            check(temporary.renameTo(target)) { "Could not save $path" }
        } finally { temporary.delete() }
    }
    /** Compare the reviewed bytes and atomically apply while all WorkspaceService writers share this lock. */
    fun compareAndApply(path: String, expected: ByteArray?, content: ByteArray?) = synchronized(fileLock(path)) {
        val file = resolve(path)
        check(!file.isDirectory) { "$path is a directory" }
        val current = if (file.exists()) file.readBytes() else null
        check(current.contentEqualsNullable(expected)) { "Later edit detected in $path; approved action refused" }
        if (content == null) delete(path) else writeBytes(path, content)
    }
    fun createDirectory(path: String) {
        val file = resolve(path)
        check(!file.isFile) { "A file already exists at $path" }
        file.mkdirs()
        resolve(path)
    }
    /** Deletes one file, or a directory tree when [recursive] is set. Symlinks are removed, never followed. */
    fun delete(path: String, recursive: Boolean = false) = synchronized(fileLockForDelete(path)) {
        require(path.isNotBlank() && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Use a normalized workspace path" }
        val link = File(root, path)
        if (Files.isSymbolicLink(link.toPath())) {
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::resolve)
            check(link.delete()) { "Could not delete $path" }
            return@synchronized
        }
        val file = resolve(path)
        if (file.isDirectory) {
            check(recursive || file.list().isNullOrEmpty()) { "Directory $path is not empty" }
            deleteTree(file.toPath())
        } else if (file.exists()) check(file.delete()) { "Could not delete $path" }
    }
    private fun fileLockForDelete(path: String): Any {
        // Use the lexical path for symlink removal; resolve() intentionally rejects links outside the root.
        require(path.isNotBlank() && !File(path).isAbsolute && path.split('/').none { it == ".." || it == "." || it.isEmpty() })
        val file = File(root, path)
        return fileLocks.getOrPut(if (Files.isSymbolicLink(file.toPath())) file.absolutePath else resolve(path).path) { Any() }
    }
    /** Removes a tree without following symbolic links. */
    private fun deleteTree(start: Path) {
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
    companion object { private val fileLocks = ConcurrentHashMap<String, Any>() }
    fun list(): List<String> = root.walkTopDown().filter { it.isFile }.map {
        val relative = it.relativeTo(root).invariantSeparatorsPath
        resolve(relative)
        relative
    }.sorted().toList()

    data class Entry(val name: String, val path: String, val isDirectory: Boolean, val size: Long)
    /** Lists one directory ("" is the root). Entries that resolve outside the workspace are skipped. */
    fun listDirectory(path: String): List<Entry> {
        val directory = if (path.isEmpty()) root else resolve(path)
        return directory.listFiles().orEmpty().mapNotNull { child ->
            val relative = if (path.isEmpty()) child.name else "$path/${child.name}"
            try {
                val file = resolve(relative)
                Entry(child.name, relative, file.isDirectory, if (file.isFile) file.length() else 0)
            } catch (_: IllegalArgumentException) { null }
        }.sortedWith(compareBy<Entry>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    /** Walks files below [path], skipping the `.git` directory and anything escaping the workspace. */
    fun walkFiles(path: String = "", limit: Int = 5000): List<String> {
        val start = if (path.isEmpty()) root else resolve(path)
        val result = ArrayList<String>()
        start.walkTopDown().onEnter { it == start || (it.name != ".git" && !Files.isSymbolicLink(it.toPath())) }.forEach { file ->
            if (result.size >= limit || !file.isFile) return@forEach
            val relative = file.relativeTo(root).invariantSeparatorsPath
            try { resolve(relative); result += relative } catch (_: IllegalArgumentException) {}
        }
        return result.sorted()
    }

    fun checkpoint(paths: List<String>): Checkpoint {
        require(paths.distinct().size == paths.size)
        val directory = File(checkpointRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val original = paths.associateWith { path ->
            val file = resolve(path)
            if (file.exists()) file.readText() else null
        }
        original.forEach { (path, text) ->
            if (text != null) File(directory, path).apply { parentFile!!.mkdirs(); writeText(text) }
        }
        return Checkpoint(original)
    }
    fun diff(checkpoint: Checkpoint): List<FileChange> = checkpoint.original.mapNotNull { (path, before) ->
        val file = resolve(path)
        val after = if (file.exists()) file.readText() else null
        if (before == after) null else FileChange(path, before, after)
    }
    /** Refuse rollback if the file changed again after the reviewed agent edit. */
    fun restore(changes: List<FileChange>) {
        changes.forEach { change ->
            val file = resolve(change.path)
            val current = if (file.exists()) file.readText() else null
            check(current == change.after) { "Later edit detected in ${change.path}; rollback refused" }
        }
        changes.forEach { change ->
            if (change.before == null) resolve(change.path).delete() else write(change.path, change.before)
        }
    }
    data class Checkpoint(val original: Map<String, String?>)
}
