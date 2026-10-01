package dev.srimi.antigravitymobile

import android.content.Context
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BuildCoordinator(private val context: Context, private val dao: BuildDao,
    private val projects: ProjectRepository, private val scope: CoroutineScope) {
    val client = BuildWorkerClient(context)
    val output = MutableStateFlow<Map<String,String>>(emptyMap())
    private val monitors = ConcurrentHashMap<String, Job>()
    private val root = File(context.filesDir, "build-runs").apply { mkdirs() }
    fun archive(id: String) = File(root, "$id/source.zip").also { P.validateId(id) }
    fun artifacts(id: String): List<File> = File(root, "$id/apks").also { P.validateId(id) }
        .listFiles().orEmpty().filter { it.extension == "apk" }.sortedBy { it.name }

    suspend fun prepare(project: ProjectRecord, text: String): BuildRecord = withContext(Dispatchers.IO) {
        val tasks = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }; P.validateTasks(tasks)
        val id = UUID.randomUUID().toString()
        val directory = projects.directory(project).canonicalFile
        check(directory.path.startsWith(projects.root.canonicalPath + File.separator)) { "Project escapes workspace storage" }
        val target = archive(id).apply { parentFile!!.mkdirs() }
        try {
            val hash = BuildSnapshot.write(directory, target)
            BuildRecord(id, project.id, tasks.joinToString(" "), "AWAITING_APPROVAL",
                "Project snapshot prepared", hash, System.currentTimeMillis()).also { dao.save(it) }
        } catch (error: Exception) { target.delete(); throw error }
    }
    suspend fun find(id: String): BuildRecord? = dao.find(id)
    suspend fun decline(id: String) {
        val record = dao.find(id) ?: return
        check(record.status == "AWAITING_APPROVAL")
        dao.save(record.copy(status="DECLINED", detail="Command was not run", finishedAt=System.currentTimeMillis()))
    }
    suspend fun approve(id: String) {
        val record = dao.find(id) ?: error("Build record missing")
        check(record.status == "AWAITING_APPROVAL") { "Approval already consumed" }
        check(hash(archive(id)) == record.snapshotHash) { "Approved snapshot changed; prepare a new build" }
        check(dao.claimApproval(id) == 1) { "Approval already consumed" }
        try {
            client.start(id, record.tasks.split(' '), archive(id), record.snapshotHash)
            val current = dao.find(id) ?: record
            if (current.status == "DISPATCHING") dao.save(current.copy(status="RUNNING", detail="Build worker running"))
        } catch (error: Exception) {
            // An IPC timeout is not evidence that the worker didn't start.
            val current = dao.find(id) ?: record
            dao.save(current.copy(detail="Start outcome unconfirmed; checking worker, never replaying"))
        }
        monitor(id)
    }
    suspend fun cancel(id: String) {
        val record = dao.find(id) ?: return
        if (record.status !in setOf("RUNNING","DISPATCHING","CANCEL_REQUESTED")) return
        dao.save(record.copy(status="CANCEL_REQUESTED", detail="Stop requested; waiting for worker confirmation"))
        runCatching { client.cancel(id) }
        monitor(id)
    }
    suspend fun recover() { dao.unfinished().forEach { monitor(it.id) } }
    fun refresh(id: String) {
        scope.launch {
            val current = dao.find(id) ?: return@launch
            if (current.status == "COMPLETED") {
                try {
                    val status = client.status(id)
                    output.update { it + (id to status.optString("output")) }
                    download(id, status); dao.save(current.copy(detail="Gradle completed; APK transfer checked"))
                }
                catch (_: Exception) { dao.save(current.copy(detail="Gradle completed; APK transfer unavailable. Refresh can retry without rebuilding.")) }
            } else monitor(id)
        }
    }
    private suspend fun download(id: String, status: org.json.JSONObject) {
        val count = status.optJSONArray("apks")?.length() ?: 0
        check(count <= 20) { "Too many APK artifacts" }
        for (index in 0 until count) client.artifact(id,index,File(root,"$id/apks/artifact-$index.apk"))
    }
    private fun monitor(id: String) {
        if (monitors[id]?.isActive == true) return
        monitors[id] = scope.launch {
            try {
                while (isActive) {
                    val current = dao.find(id) ?: break
                    if (current.status !in setOf("RUNNING","DISPATCHING","CANCEL_REQUESTED")) break
                    try {
                        val status = client.status(id)
                        val phase = status.optString("status")
                        if (phase == "RUNNING" && current.status == "CANCEL_REQUESTED") runCatching { client.cancel(id) }
                        if (phase == "NOT_FOUND") {
                            dao.save(current.copy(status="INTERRUPTED",detail="Worker has no command record; action was not replayed",finishedAt=System.currentTimeMillis())); break
                        }
                        output.update { it + (id to status.optString("output")) }
                        val next = current.copy(status=if (phase in P.terminal) phase else current.status,
                            detail=status.optString("detail"), finishedAt=status.optLong("finishedAt"),durationMs=status.optLong("durationMs"))
                        dao.save(next)
                        if (phase == "COMPLETED") {
                            try {
                                download(id, status)
                                dao.save(next.copy(detail="Gradle completed; ${status.optJSONArray("apks")?.length() ?: 0} APK(s) transferred"))
                            }
                            catch (_: Exception) { dao.save(next.copy(detail="Gradle completed; APK transfer failed. Tap Refresh to retry the transfer.")) }
                        }
                        if (phase in P.terminal) break
                    } catch (_: Exception) {
                        // Retain uncertain state through transient observation failures.
                    }
                    delay(1500)
                }
            } finally { monitors.remove(id) }
        }
    }
    private fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536); while(true) { val n=input.read(bytes); if(n<0)break; digest.update(bytes,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
