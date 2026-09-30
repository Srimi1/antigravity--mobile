package dev.srimi.antigravitymobile.runtime

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A website root, not an app-data file server. Used on both sides of the UID boundary. */
object WebFiles {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_FILES = 10000
    fun relative(path: String): String {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && path.none { it.code < 32 || it.code == 127 } && ':' !in path) { "Use a relative website path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.startsWith('.') || it == "node_modules" }) { "Hidden files, dependencies and traversal are not website content" }
        return path
    }
    fun resolve(root: File, path: String): File {
        relative(path)
        check(!Files.isSymbolicLink(root.toPath())) { "Website root cannot be a link" }
        var current = root
        path.split('/').forEach { part -> current = File(current, part); check(!Files.isSymbolicLink(current.toPath())) { "Website links are unsupported" } }
        val file = current.canonicalFile
        check(file.path.startsWith(root.canonicalPath + File.separator)) { "Path escapes website" }
        return file
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun snapshot(root: File, destination: File): String {
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Website folder missing or linked" }
        check(!destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Snapshot must be outside website" }
        fun sources(): List<Pair<String, File>> = root.walkTopDown().onEnter {
            if (it == root) true else if (it.name.startsWith('.') || it.name == "node_modules") false
            else { check(!Files.isSymbolicLink(it.toPath())) { "Website links are unsupported" }; true }
        }.filter { !it.isDirectory && !it.name.startsWith('.') }.map {
            val path = it.relativeTo(root).invariantSeparatorsPath
            val file = resolve(root, path); check(file.isFile) { "Unsupported website file" }; path to file
        }.take(MAX_FILES + 1).toList().sortedBy { it.first }.also { check(it.size <= MAX_FILES) { "Website exceeds $MAX_FILES files" } }
        val entries = sources(); val copied = mutableMapOf<String, String>(); var total = 0L
        try {
            ZipOutputStream(destination.outputStream()).use { zip -> entries.forEach { (path, file) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Website copy stopped")
                    val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= MAX_BYTES) { "Website exceeds 64 MB" }; digest.update(buffer, 0, n); zip.write(buffer, 0, n)
                } }
                zip.closeEntry(); copied[path] = digest.digest().joinToString("") { "%02x".format(it) }
            } }
            val after = sources()
            check(after.map { it.first } == entries.map { it.first } && after.all { (path, file) -> sha256(file) == copied[path] }) { "Website changed while copying; try again" }
            return sha256(destination)
        } catch (error: Exception) { destination.delete(); throw error }
    }
    fun extract(input: InputStream, root: File) {
        var total = 0L; var count = 0; val seen = mutableSetOf<String>()
        ZipInputStream(input).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            check(++count <= MAX_FILES) { "Website has too many entries" }
            val path = entry.name.removeSuffix("/")
            val file = resolve(root, path); check(seen.add(path)) { "Duplicate website entry" }
            if (entry.isDirectory) check(file.mkdirs() || file.isDirectory) else {
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                check(!file.exists()) { "Duplicate website file" }
                file.outputStream().use { output -> val buffer = ByteArray(65536); while (true) {
                    val n = zip.read(buffer); if (n < 0) break
                    total += n; check(total <= MAX_BYTES) { "Website exceeds 64 MB" }; output.write(buffer, 0, n)
                } }
            }
        } }
    }
}
