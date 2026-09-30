package dev.srimi.antigravitymobile

import java.io.File
import java.util.UUID

/** Owns one app-private workspace. This is not a shell sandbox. */
class WorkspaceService(private val root: File, private val checkpointRoot: File) {
    init { root.mkdirs(); checkpointRoot.mkdirs() }
    private fun resolve(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Use a relative workspace path" }
        require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Use a normalized workspace path" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Path escapes workspace" }
        return file
    }
    fun read(path: String): String = resolve(path).readText()
    fun write(path: String, text: String) {
        val file = resolve(path)
        file.parentFile!!.mkdirs()
        // Resolve again after parent creation, preserving the symlink boundary check.
        resolve(path).writeText(text)
    }
    fun list(): List<String> = root.walkTopDown().filter { it.isFile }.map {
        val relative = it.relativeTo(root).invariantSeparatorsPath
        resolve(relative)
        relative
    }.sorted().toList()
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
