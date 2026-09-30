package dev.srimi.antigravitymobile.worker

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import dev.srimi.antigravitymobile.runtime.WebFiles
import org.json.JSONObject
import java.io.File

/** Receives only the approved website copy, never a main-app path. */
class WebPreviewStore(context: Context) {
    private val previews = File(context.filesDir, "web-previews").apply { mkdirs() }
    fun prepare(id: String, hash: String, entry: String, descriptor: ParcelFileDescriptor) {
        P.validateId(id); WebFiles.relative(entry)
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid snapshot hash" }
        val session = File(previews, id)
        check(session.mkdir()) { "Preview ID was already used; approve a new copy" }
        val archive = File(session, "source.zip")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> archive.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break
                    total += n; check(total <= WebFiles.MAX_BYTES + 4L * 1024 * 1024) { "Website archive too large" }; output.write(buffer, 0, n)
                }
            } }
            check(WebFiles.sha256(archive) == hash) { "Website copy hash changed" }
            val site = File(session, "site").apply { check(mkdir()) }
            archive.inputStream().use { WebFiles.extract(it, site) }
            check(WebFiles.resolve(site, entry).isFile && entry.substringAfterLast('.').lowercase() in setOf("html", "htm")) { "HTML entry file missing" }
            val atomic = AtomicFile(File(session, "ready.json")); val out = atomic.startWrite()
            try { out.write(JSONObject().put("entry", entry).put("sha256", hash).toString().toByteArray()); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
        } catch (error: Exception) {
            // Retain an empty ID claim, not partial files or an executable session.
            File(session, "site").deleteRecursively(); throw error
        } finally { archive.delete(); descriptor.close() }
    }
    fun load(id: String): Pair<File, String> {
        P.validateId(id)
        val session = File(previews, id)
        check(!File(session, "opened").exists()) { "Preview already opened; approve a new copy in Projects" }
        val record = JSONObject(File(session, "ready.json").readText())
        val entry = record.getString("entry"); val root = File(session, "site")
        check(WebFiles.resolve(root, entry).isFile) { "Website copy unavailable" }
        check(File(session, "opened").createNewFile()) { "Preview already opened; approve a new copy in Projects" }
        return root to entry
    }
    fun close(id: String) {
        P.validateId(id)
        File(previews, "$id/site").deleteRecursively()
    }
}
