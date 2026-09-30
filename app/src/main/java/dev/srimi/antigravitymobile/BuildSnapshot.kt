package dev.srimi.antigravitymobile

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Immutable source copy; excludes repository metadata, SDK paths and Gradle outputs.
 * Links are refused rather than followed into a different source tree. */
object BuildSnapshot {
    private const val MAX_BYTES = 512L * 1024 * 1024
    private fun sources(base: File): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        fun visit(file: File, path: String) {
            if (file.name in setOf(".git", ".gradle", ".kotlin") || file.name == "local.properties" ||
                (file.name == "build" && listOf("build.gradle", "build.gradle.kts").any { File(file.parentFile,it).isFile })) return
            check(!Files.isSymbolicLink(file.toPath())) { "Build source links are unsupported: $path" }
            val canonical = file.canonicalFile
            check(canonical == base || canonical.path.startsWith(base.path + File.separator)) { "Source escapes project: $path" }
            when {
                file.isDirectory -> (file.listFiles() ?: error("Cannot list source: $path")).sortedBy { it.name }.forEach {
                    visit(it, if (path.isEmpty()) it.name else "$path/${it.name}")
                }
                file.isFile -> { check(result.size < 50000) { "Too many source files" }; result += path to file }
                else -> error("Unsupported source entry: $path")
            }
        }
        visit(base, "")
        return result
    }
    fun write(project: File, destination: File): String {
        check(!Files.isSymbolicLink(project.toPath())) { "Project root cannot be a link" }
        val base = project.canonicalFile
        check(base.isDirectory) { "Project directory missing" }
        check(!destination.canonicalPath.startsWith(base.path + File.separator)) { "Snapshot must be outside the project" }
        var bytes = 0L
        val entries = sources(base)
        val copied = mutableMapOf<String,String>()
        ZipOutputStream(destination.outputStream()).use { zip ->
            entries.forEach { (path, file) ->
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        bytes += n; check(bytes <= MAX_BYTES) { "Source snapshot exceeds 512 MB" }
                        zip.write(buffer,0,n); digest.update(buffer,0,n)
                    }
                }
                zip.closeEntry(); copied[path] = hex(digest.digest())
            }
        }
        val after = sources(base)
        check(after.map { it.first } == entries.map { it.first } && after.all { (path,file) -> sha256(file) == copied[path] }) {
            "Project changed while preparing; create a new snapshot"
        }
        return sha256(destination)
    }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes,0,n) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
