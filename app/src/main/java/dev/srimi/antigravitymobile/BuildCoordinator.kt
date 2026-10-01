package dev.srimi.antigravitymobile

import android.content.Context
import dev.srimi.antigravitymobile.runtime.BuildEvidenceStore
import dev.srimi.antigravitymobile.runtime.BuildProtocol as P
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BuildCoordinator(private val context: Context, private val dao: BuildDao,
    private val projects: ProjectRepository, private val scope: CoroutineScope) {
    val client = BuildWorkerClient(context)
    val output = MutableStateFlow<Map<String, String>>(emptyMap())
    private val monitors = ConcurrentHashMap<String, Job>()
    private val transfers = ConcurrentHashMap<String, Job>()
    private val transferLocks = ConcurrentHashMap<String, Mutex>()
    private val root = File(context.filesDir, "build-runs").apply { mkdirs() }
    private val evidence = BuildEvidenceStore(root)
    fun archive(id: String): File { P.validateId(id); return File(root, "$id/source.zip") }
    fun artifacts(id: String): List<File> = evidence.artifacts(id)
    fun logReference(id: String) = evidence.logReference(id)
    suspend fun log(id: String): String = withContext(Dispatchers.IO) { evidence.log(id) }

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
    suspend fun find(id: String) = dao.find(id)
    suspend fun decline(id: String) = resolvePending(id, "DECLINED")
    suspend fun resolvePending(id: String, status: String) {
        require(status in setOf("DECLINED", "CANCELLED", "INTERRUPTED"))
        dao.resolvePending(id, status, "Approval ${status.lowercase()}; command was not dispatched", System.currentTimeMillis())
    }
    suspend fun approve(id: String) {
        val record = dao.find(id) ?: error("Build record missing")
        check(record.status == "AWAITING_APPROVAL") { "Approval already consumed" }
        check(BuildEvidenceStore.hash(archive(id)) == record.snapshotHash) { "Approved snapshot changed; prepare a new build" }
        check(dao.claimApproval(id) == 1) { "Approval already consumed" }
        try {
            client.start(id, record.tasks.split(' '), archive(id), record.snapshotHash)
            val current = dao.find(id) ?: record
            if (current.status == "DISPATCHING") dao.save(current.copy(status = "RUNNING", detail = "Build worker running"))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancel(id); monitor(id) }
            throw cancelled
        } catch (_: Exception) {
            // Even an IPC start timeout may have dispatched the command. Query only, never resend START.
            val current = dao.find(id) ?: record
            dao.save(current.copy(detail = "Start outcome unconfirmed; checking worker, never replaying"))
        }
        monitor(id)
    }
    suspend fun cancel(id: String) {
        val record = dao.find(id) ?: return
        if (record.status == "AWAITING_APPROVAL") { resolvePending(id, "CANCELLED"); return }
        if (record.status !in setOf("RUNNING", "DISPATCHING", "CANCEL_REQUESTED")) return
        dao.save(record.copy(status = "CANCEL_REQUESTED", detail = "Stop requested; waiting for worker confirmation"))
        runCatching { client.cancel(id) }
        monitor(id)
    }
    suspend fun recover() {
        dao.interruptPending(System.currentTimeMillis())
        dao.unfinished().forEach { monitor(it.id) }
    }
    fun refresh(id: String) {
        synchronized(transfers) {
            if (transfers[id]?.isCompleted == false) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val current = dao.find(id) ?: return@launch
                    if (current.status == "COMPLETED") {
                        try {
                            val status = client.status(id)
                            check(status.optString("status") == "COMPLETED") { "Worker result unavailable" }
                            saveLog(id, status)
                            transfer(current, status)
                        } catch (_: Exception) {
                            val latest = dao.find(id) ?: current
                            val cached = latest.artifactState == "READY" && artifacts(id).isNotEmpty()
                            dao.save(latest.copy(artifactState = if (cached) "READY" else "FAILED",
                                detail = if (cached) "Gradle completed; recorded verified APKs retained, worker unavailable" else
                                    "Gradle completed; APK transfer unavailable. Refresh can retry without rebuilding."))
                        }
                    } else monitor(id)
                } finally { transfers.remove(id, currentCoroutineContext()[Job]) }
            }
            transfers[id] = job; job.start()
        }
    }
    private fun saveLog(id: String, status: JSONObject) {
        val text = status.optString("output")
        if (text.isNotEmpty()) evidence.saveLog(id, text)
        output.update { it + (id to evidence.log(id)) }
    }
    private suspend fun transfer(record: BuildRecord, status: JSONObject) = transferLocks.getOrPut(record.id) { Mutex() }.withLock {
        val count = status.optJSONArray("apks")?.length() ?: 0
        check(count in 0..20) { "Too many APK artifacts" }
        val latest = dao.find(record.id) ?: error("Build record missing")
        if (latest.artifactState == "NONE" && count == 0) return@withLock
        if (latest.artifactState == "READY" && artifacts(record.id).size == count && count > 0) return@withLock
        val files = (0 until count).map { index ->
            File(root, "${record.id}/apks/artifact-$index.apk").also { client.artifact(record.id, index, it) }
        }
        evidence.commitArtifacts(record.id, files)
        dao.save(latest.copy(artifactState = if (count == 0) "NONE" else "READY",
            detail = "Gradle completed; $count verified APK(s) transferred"))
    }
    private fun monitor(id: String) {
        synchronized(monitors) {
            if (monitors[id]?.isCompleted == false) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (isActive) {
                        val before = dao.find(id) ?: break
                        if (before.status == "COMPLETED" && before.artifactState == "PENDING") { refresh(id); break }
                        if (before.status !in setOf("RUNNING", "DISPATCHING", "CANCEL_REQUESTED")) break
                        try {
                            val status = client.status(id)
                            val current = dao.find(id) ?: break
                            val phase = status.optString("status")
                            check(phase in P.terminal || phase in setOf("RUNNING", "NOT_FOUND")) { "Unknown worker state" }
                            if (phase == "RUNNING" && current.status == "CANCEL_REQUESTED") runCatching { client.cancel(id) }
                            if (phase == "NOT_FOUND") {
                                dao.save(current.copy(status = "INTERRUPTED", artifactState = "NONE",
                                    detail = "Worker has no command record; outcome unconfirmed, action never replayed", finishedAt = System.currentTimeMillis()))
                                break
                            }
                            saveLog(id, status)
                            val next = current.copy(status = if (phase in P.terminal) phase else current.status,
                                detail = status.optString("detail"), finishedAt = status.optLong("finishedAt"),
                                durationMs = status.optLong("durationMs").coerceAtLeast(0),
                                artifactState = if (phase == "COMPLETED") "PENDING" else if (phase in P.terminal) "NONE" else current.artifactState)
                            dao.save(next)
                            if (phase == "COMPLETED") {
                                try { transfer(next, status) }
                                catch (_: Exception) { dao.save(next.copy(artifactState = "FAILED", detail = "Gradle completed; APK transfer failed. Refresh retries the transfer only.")) }
                            }
                            if (phase in P.terminal) break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Retain uncertain state during transient observation failures. */ }
                        delay(1500)
                    }
                } finally { monitors.remove(id, currentCoroutineContext()[Job]) }
            }
            monitors[id] = job; job.start()
        }
    }
}
