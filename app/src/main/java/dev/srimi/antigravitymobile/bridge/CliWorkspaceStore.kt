package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.BuildSnapshot
import dev.srimi.antigravitymobile.ChangeService
import dev.srimi.antigravitymobile.WorkspaceService
import dev.srimi.antigravitymobile.contentEqualsNullable
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Bounded source copies and a durable baseline. CLI files never mount or directly overwrite a project. */
class CliWorkspaceStore(private val root: File, private val limits: Limits = Limits()) {
    data class Limits(val files: Int = 10_000, val fileBytes: Long = 8L * 1024 * 1024,
        val totalBytes: Long = 64L * 1024 * 1024, val archiveBytes: Long = 72L * 1024 * 1024)
    data class Snapshot(val taskId: String, val projectId: String, val archive: File, val archiveHash: String,
        val hashes: Map<String, String>)
    init {
        require(limits.files > 0 && limits.fileBytes > 0 && limits.totalBytes > 0 && limits.archiveBytes > 0)
        check(!Files.isSymbolicLink(root.toPath())) { "Private workspace root cannot be a link" }
        root.mkdirs()
    }
    private fun directory(task: String): File {
        BridgeSecurity.checkPair(task)
        return File(root, task).also { check(!Files.isSymbolicLink(it.toPath())) { "Private workspace cannot be a link" } }
    }
    companion object {
        private val excluded = setOf(".git", ".gradle", ".kotlin", ".signing", ".codex", ".claude", ".gemini", ".agents", ".config",
            ".antigravity", ".agm", "node_modules", "local.properties", "credentials.json", "id_rsa", "id_ed25519")
        /** Same policy applies to outbound snapshots and inbound changes, including new files. */
        fun allowed(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.toByteArray().size > 512 || path.any(Char::isISOControl)) return false
            val parts = path.split('/')
            return parts.size <= 32 && parts.none { part -> part.isEmpty() || part == "." || part == ".." ||
                part in excluded || part == "build" || part == ".env" || part.startsWith(".env.") ||
                part.endsWith(".p12", true) || part.endsWith(".pem", true) }
        }
    }
    private fun sources(project: File): List<Pair<String, File>> {
        check(project.isDirectory && !Files.isSymbolicLink(project.toPath())) { "Project directory missing or linked" }
        val base = project.canonicalFile
        val result = mutableListOf<Pair<String, File>>()
        fun visit(file: File, path: String) {
            if (path.isNotEmpty() && !allowed(path)) return
            check(!Files.isSymbolicLink(file.toPath())) { "CLI source links are unsupported" }
            check(file.canonicalFile == base || file.canonicalPath.startsWith(base.path + File.separator)) { "CLI source escapes project" }
            when {
                file.isDirectory -> (file.listFiles() ?: error("Cannot list CLI source")).sortedBy { it.name }.forEach {
                    visit(it, if (path.isEmpty()) it.name else "$path/${it.name}")
                }
                file.isFile -> {
                    check(result.size < limits.files && file.length() <= limits.fileBytes) { "CLI source exceeds limits" }
                    result += path to file
                }
                else -> error("Unsupported CLI source entry")
            }
        }
        visit(project, "")
        return result
    }
    fun create(task: String, projectId: String, project: File): Snapshot {
        BridgeSecurity.checkPair(projectId)
        val target = directory(task)
        check(!target.canonicalPath.startsWith(project.canonicalPath + File.separator) && target.canonicalFile != project.canonicalFile) { "CLI source snapshot must be outside project" }
        // Only the invocation that reserves this directory may write or clean it up.
        Files.createDirectory(target.toPath())
        try {
            val before = File(target, "before").apply { mkdirs() }
            val archive = File(target, "source.zip")
            val hashes = linkedMapOf<String, String>()
            var total = 0L
            val entries = sources(project)
            ZipOutputStream(archive.outputStream()).use { zip ->
                entries.forEach { (path, file) ->
                    val copy = File(before, path).apply { parentFile!!.mkdirs() }
                    var size = 0L
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                    copy.outputStream().use { output -> file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            size += count; total += count
                            check(size <= limits.fileBytes && total <= limits.totalBytes) { "CLI source exceeds limits" }
                            output.write(buffer, 0, count); zip.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    } }
                    zip.closeEntry(); hashes[path] = BuildSnapshot.sha256(copy)
                }
            }
            check(archive.length() <= limits.archiveBytes) { "CLI source archive exceeds limits" }
            val after = sources(project)
            check(after.map { it.first } == entries.map { it.first } && after.all { (path, file) -> BuildSnapshot.sha256(file) == hashes[path] }) {
                "Project changed while exporting; create a new task"
            }
            val hash = BuildSnapshot.sha256(archive)
            val manifest = JSONObject().put("v", 1).put("taskId", task).put("projectId", projectId)
                .put("archiveHash", hash).put("files", JSONObject(hashes))
            val temporary = File(target, "manifest.tmp")
            temporary.outputStream().use { output -> output.write(manifest.toString().toByteArray()); output.fd.sync() }
            check(temporary.renameTo(File(target, "manifest.json"))) { "Could not save CLI source baseline" }
            return Snapshot(task, projectId, archive, hash, hashes)
        } catch (error: Exception) { target.deleteRecursively(); throw error }
    }
    fun recorded(task: String): Snapshot {
        val target = directory(task)
        val manifest = File(target, "manifest.json")
        check(manifest.isFile && manifest.length() <= 8L * 1024 * 1024 && !Files.isSymbolicLink(manifest.toPath())) { "CLI source baseline unavailable" }
        val value = BridgeSecurity.json(manifest.readText(), 8 * 1024 * 1024)
        BridgeSecurity.fields(value, setOf("v", "taskId", "projectId", "archiveHash", "files"))
        check(value.get("v") == 1 && value.requiredText("taskId", 64) == task)
        val files = value.getJSONObject("files")
        check(files.length() <= limits.files)
        val hashes = files.keys().asSequence().associateWith { path ->
            check(allowed(path)); files.requiredText(path, 64).also { check(Regex("[a-f0-9]{64}").matches(it)) }
        }
        val archive = File(target, "source.zip")
        val hash = value.requiredText("archiveHash", 64)
        check(archive.isFile && archive.length() <= limits.archiveBytes && !Files.isSymbolicLink(archive.toPath()) && BuildSnapshot.sha256(archive) == hash) { "Recorded CLI source archive changed" }
        return Snapshot(task, value.requiredText("projectId", 64), archive, hash, hashes)
    }
    /** A full bounded archive is compared with the baseline, never extracted into the native project. */
    fun differences(task: String, returnedArchive: File): List<ChangeService.FileDiff> {
        val snapshot = recorded(task)
        check(returnedArchive.isFile && returnedArchive.length() <= limits.archiveBytes && !Files.isSymbolicLink(returnedArchive.toPath())) { "CLI result archive unavailable or exceeds limits" }
        val target = directory(task)
        val after = File(target, "returned-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val paths = linkedSetOf<String>()
            val files = linkedSetOf<String>()
            var total = 0L
            ZipFile(returnedArchive).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val path = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                    check(allowed(path) && paths.add(path) && paths.size <= limits.files) { "CLI result contains forbidden or duplicate paths" }
                    check(entry.size in 0..limits.fileBytes && entry.crc >= 0) { "CLI result entry exceeds limits" }
                    if (entry.isDirectory) { check(entry.size == 0L); return@forEach }
                    files += path
                    val file = File(after, path)
                    check(file.canonicalPath.startsWith(after.canonicalPath + File.separator)) { "CLI result path escapes workspace" }
                    file.parentFile!!.mkdirs()
                    var size = 0L
                    val crc = CRC32()
                    file.outputStream().use { output -> zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            size += count; total += count
                            check(size <= limits.fileBytes && total <= limits.totalBytes) { "CLI result exceeds limits" }
                            output.write(buffer, 0, count)
                            crc.update(buffer, 0, count)
                        }
                    } }
                    check(size == entry.size && crc.value == entry.crc) { "CLI result archive is corrupt" }
                }
            }
            return (snapshot.hashes.keys + files).sorted().mapNotNull { path ->
                val beforeFile = File(target, "before/$path")
                val before = snapshot.hashes[path]?.let { hash ->
                    check(beforeFile.isFile && beforeFile.canonicalPath.startsWith(File(target, "before").canonicalPath + File.separator) &&
                        !Files.isSymbolicLink(beforeFile.toPath()) && beforeFile.length() <= limits.fileBytes && BuildSnapshot.sha256(beforeFile) == hash) { "Recorded CLI baseline changed" }
                    beforeFile.readBytes()
                }
                val content = if (path in files) File(after, path).readBytes() else null
                if (before.contentEqualsNullable(content)) null else ChangeService.FileDiff(path, before, content)
            }
        } finally { after.deleteRecursively() }
    }
    /** Check the complete import before any native write, then each write also uses compare-and-apply. */
    fun checkConflicts(workspace: WorkspaceService, differences: List<ChangeService.FileDiff>) {
        differences.forEach { diff ->
            check(allowed(diff.path)) { "CLI import path forbidden" }
            val current = if (workspace.exists(diff.path) && !workspace.isDirectory(diff.path)) workspace.readBytes(diff.path) else null
            check(!workspace.exists(diff.path) || !workspace.isDirectory(diff.path)) { "CLI import conflicts with directory" }
            check(current.contentEqualsNullable(diff.before)) { "Later edit detected in ${diff.path}; CLI import refused" }
        }
    }
}
