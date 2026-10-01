package dev.srimi.antigravitymobile.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Artifact references and observed worker log tails survive a main-process restart. */
class BuildEvidenceStore(private val root: File) {
    private fun folder(id: String): File { BuildProtocol.validateId(id); return File(root, id) }
    fun logReference(id: String): String { folder(id); return "build:$id/log-tail" }
    fun log(id: String): String = File(folder(id), "worker-log-tail.txt").takeIf { it.isFile }?.readText().orEmpty()
    @Synchronized fun saveLog(id: String, text: String) { atomic(File(folder(id), "worker-log-tail.txt"), text.takeLast(12_000)) }
    fun commitArtifacts(id: String, files: List<File>) {
        require(files.size <= 20)
        val parent = File(folder(id), "apks").canonicalFile
        val entries = JSONArray()
        files.forEach { file ->
            require(file.parentFile?.canonicalFile == parent && file.canonicalFile == File(parent, file.name) && file.isFile)
            require(file.name.matches(Regex("artifact-[0-9]+\\.apk")))
            entries.put(JSONObject().put("name", file.name).put("size", file.length()).put("sha256", hash(file)))
        }
        atomic(File(folder(id), "artifacts.json"), JSONObject().put("id", id).put("apks", entries).toString())
    }
    fun artifacts(id: String): List<File> {
        val manifest = File(folder(id), "artifacts.json")
        if (!manifest.isFile || manifest.length() > 20_000) return emptyList()
        return runCatching {
            val data = JSONObject(manifest.readText())
            require(data.getString("id") == id)
            val entries = data.getJSONArray("apks"); require(entries.length() <= 20)
            val parent = File(folder(id), "apks").canonicalFile
            val files = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index); val name = entry.getString("name")
                require(name.matches(Regex("artifact-[0-9]+\\.apk")))
                val file = File(parent, name)
                require(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == entry.getLong("size"))
                require(hash(file) == entry.getString("sha256")) { "Recorded APK changed" }
                file
            }
            require(files.map { it.name }.distinct().size == files.size)
            files
        }.getOrDefault(emptyList())
    }
    private fun atomic(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { stream -> stream.write(text.toByteArray()); stream.fd.sync() }
        check(temp.renameTo(file)) { "Could not persist build evidence" }
    }
    companion object {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val bytes = ByteArray(65536)
                while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
