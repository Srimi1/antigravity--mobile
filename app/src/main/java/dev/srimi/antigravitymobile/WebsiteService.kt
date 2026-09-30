package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.BuildProtocol
import dev.srimi.antigravitymobile.runtime.WebFiles
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile

data class WebsiteCopy(
    val id: String, val projectId: String, val root: String, val entry: String,
    val hash: String, val files: List<String>, val bytes: Long, val status: String,
)

/** Durable, one-use approvals. Neither an interrupted dispatch nor an old preview is replayed. */
class WebsiteService(private val copies: File) {
    init { check(copies.mkdirs() || copies.isDirectory) }
    private val gate = Any()
    private fun folder(id: String) = File(copies, id).also { BuildProtocol.validateId(id) }
    fun archive(id: String) = File(folder(id), "source.zip")
    private fun record(id: String) = File(folder(id), "approval.properties")

    fun prepare(projectId: String, project: File, rootPath: String, entry: String): WebsiteCopy {
        val root = if (rootPath.isEmpty()) project else WebFiles.resolve(project, rootPath)
        WebFiles.relative(entry)
        check(entry.substringAfterLast('.').lowercase() in setOf("html", "htm") && WebFiles.resolve(root, entry).isFile) {
            "Choose an existing HTML entry inside the website folder"
        }
        val id = UUID.randomUUID().toString()
        check(folder(id).mkdir())
        try {
            val hash = WebFiles.snapshot(root, archive(id))
            val (paths, bytes) = ZipFile(archive(id)).use { zip ->
                val entries = zip.entries().asSequence().toList()
                entries.map { it.name } to entries.sumOf { it.size }
            }
            val copy = WebsiteCopy(id, projectId, rootPath, entry, hash, paths, bytes, "AWAITING_APPROVAL")
            save(copy)
            return copy
        } catch (error: Throwable) { Archives.deleteTree(folder(id)); throw error }
    }

    fun find(id: String): WebsiteCopy {
        val p = Properties().apply { record(id).inputStream().use { load(it) } }
        return WebsiteCopy(id, p.getProperty("projectId"), p.getProperty("root"), p.getProperty("entry"),
            p.getProperty("hash"), p.getProperty("files").split('\n').filter { it.isNotEmpty() },
            p.getProperty("bytes").toLong(), p.getProperty("status"))
    }

    fun claim(id: String): WebsiteCopy = synchronized(gate) {
        val copy = find(id)
        check(copy.status == "AWAITING_APPROVAL") { "Approval already consumed; prepare a new preview" }
        check(WebFiles.sha256(archive(id)) == copy.hash) { "Website copy changed; prepare a new preview" }
        copy.copy(status = "DISPATCHING").also(::save)
    }

    fun decline(id: String) = synchronized(gate) {
        val copy = find(id)
        check(copy.status == "AWAITING_APPROVAL") { "Approval already consumed" }
        save(copy.copy(status = "DECLINED")); archive(id).delete()
    }

    fun finish(id: String, status: String) = synchronized(gate) {
        require(status in setOf("READY", "EXPORTED", "INTERRUPTED"))
        val copy = find(id)
        check(copy.status == "DISPATCHING") { "Preview no longer dispatching" }
        save(copy.copy(status = status))
        archive(id).delete()
    }

    /** Called once at process startup, never while a live dispatch is running. */
    fun recover() = synchronized(gate) {
        copies.listFiles().orEmpty().filter { it.isDirectory }.forEach { directory ->
            runCatching {
                val copy = find(directory.name)
                if (copy.status in setOf("AWAITING_APPROVAL", "DISPATCHING")) {
                    save(copy.copy(status = "INTERRUPTED")); archive(copy.id).delete()
                }
            }
        }
    }

    private fun save(copy: WebsiteCopy) {
        val p = Properties().apply {
            setProperty("projectId", copy.projectId); setProperty("root", copy.root); setProperty("entry", copy.entry)
            setProperty("hash", copy.hash); setProperty("files", copy.files.joinToString("\n"))
            setProperty("bytes", copy.bytes.toString()); setProperty("status", copy.status)
        }
        val temporary = File(folder(copy.id), "approval.pending")
        FileOutputStream(temporary).use { output -> p.store(output, "Website approval"); output.fd.sync() }
        Files.move(temporary.toPath(), record(copy.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
