package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.BuildSnapshot
import dev.srimi.antigravitymobile.runtime.AgentBackend
import dev.srimi.antigravitymobile.runtime.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** All RPCs are paired, bounded and task-scoped. A failed send is never retried here. */
class PairedCliBridge(
    private val connect: suspend () -> PairedBridgeConnection,
    private val capability: suspend (AgentBackend) -> ToolOutcome.RuntimeUnavailable?,
    private val helperRoot: String = "/root/agm-work/bridge",
) : CliBridgeEndpoint {
    override suspend fun unavailable(backend: AgentBackend) = capability(backend)
    private suspend fun call(task: String, op: String, data: JSONObject = JSONObject()): JSONObject {
        BridgeSecurity.checkPair(task)
        return connect().use { it.call(data.put("op", op).put("taskId", task)) }
    }
    private fun state(task: String, value: JSONObject): CliWorkerState = BridgeSecurity.guard {
        if (value.requiredText("id", 64) != task) throw BridgeProtocolException()
        val phase = value.requiredText("state", 32)
        if (phase !in setOf("NOT_FOUND", "STARTING", "RUNNING", "EXITED", "CANCEL_REQUESTED", "CANCELLED", "INTERRUPTED")) throw BridgeProtocolException()
        val count = value.integer("events")
        if (count !in 0..5000) throw BridgeProtocolException()
        val exit = if (value.isNull("exitCode")) null else value.integer("exitCode").also {
            if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw BridgeProtocolException()
        }.toInt()
        CliWorkerState(task, phase, count, exit, value.get("cancellationUnconfirmed") as? Boolean ?: throw BridgeProtocolException())
    }
    override suspend fun prepare(snapshot: CliWorkspaceStore.Snapshot): String = withContext(Dispatchers.IO) {
        check(BuildSnapshot.sha256(snapshot.archive) == snapshot.archiveHash) { "Recorded CLI source archive changed" }
        val size = snapshot.archive.length()
        val initialized = call(snapshot.taskId, "upload", JSONObject().put("size", size).put("hash", snapshot.archiveHash))
        if (initialized.integer("size") != size || initialized.requiredText("hash", 64) != snapshot.archiveHash) throw BridgeProtocolException()
        var offset = 0L
        snapshot.archive.inputStream().use { input ->
            val buffer = ByteArray(48 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                val reply = call(snapshot.taskId, "upload_chunk", JSONObject().put("offset", offset)
                    .put("data", BridgeSecurity.base64(buffer.copyOf(count))))
                offset += count
                if (reply.integer("received") !in offset..size) throw BridgeProtocolException()
            }
        }
        if (offset != size) throw BridgeProtocolException()
        val ready = call(snapshot.taskId, "upload_commit")
        val expected = "$helperRoot/workspaces/${snapshot.taskId}/source"
        if (ready.requiredText("cwd", 1024) != expected || ready.requiredText("hash", 64) != snapshot.archiveHash) throw BridgeProtocolException()
        expected
    }
    override suspend fun start(taskId: String, backend: AgentBackend, sessionId: String?): Boolean {
        val id = when (backend) { AgentBackend.Codex -> "codex"; AgentBackend.AntigravityCli -> "antigravity"; else -> throw BridgeProtocolException() }
        val request = JSONObject().put("backend", id)
        sessionId?.let { request.put("conversationId", it) }
        return call(taskId, "start", request).get("started") as? Boolean ?: throw BridgeProtocolException()
    }
    override suspend fun send(taskId: String, commandId: String, message: JSONObject): Boolean {
        BridgeSecurity.checkPair(commandId)
        return call(taskId, "send", JSONObject().put("commandId", commandId).put("message", message)).get("sent") as? Boolean ?: throw BridgeProtocolException()
    }
    override suspend fun observe(taskId: String, after: Long): CliPage {
        require(after >= 0)
        val reply = call(taskId, "observe", JSONObject().put("after", after))
        val status = state(taskId, reply.getJSONObject("status"))
        val events = reply.getJSONArray("events")
        if (events.length() > 100) throw BridgeProtocolException()
        val result = (0 until events.length()).map { index -> events.getJSONObject(index).also { event ->
            if (event.integer("sequence") != after + index + 1 || event.integer("sequence") > status.eventCount ||
                event.requiredText("kind", 32) !in setOf("cli", "exit", "permission_unavailable", "native_call")) throw BridgeProtocolException()
        } }
        return CliPage(status, result)
    }
    override suspend fun status(taskId: String) = state(taskId, call(taskId, "status"))
    override suspend fun cancel(taskId: String) = state(taskId, call(taskId, "cancel"))
    override suspend fun capture(taskId: String, destination: File): File = withContext(Dispatchers.IO) {
        val meta = call(taskId, "capture")
        val size = meta.integer("size"); val digest = meta.requiredText("hash", 64)
        if (size !in 0..72L * 1024 * 1024 || !Regex("[a-f0-9]{64}").matches(digest)) throw BridgeProtocolException()
        destination.parentFile!!.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                var offset = 0L
                while (offset < size) {
                    val chunk = call(taskId, "download", JSONObject().put("offset", offset).put("hash", digest))
                    if (chunk.integer("offset") != offset || chunk.integer("size") != size || chunk.requiredText("hash", 64) != digest) throw BridgeProtocolException()
                    val bytes = BridgeSecurity.unbase64(chunk.requiredText("data", 66_000))
                    if (bytes.isEmpty() || bytes.size > 48 * 1024 || offset + bytes.size > size) throw BridgeProtocolException()
                    output.write(bytes); offset += bytes.size
                }
                output.fd.sync()
            }
            if (BuildSnapshot.sha256(temporary) != digest) throw BridgeProtocolException()
            check(temporary.renameTo(destination)) { "Could not save CLI result archive" }
            destination
        } finally { temporary.delete() }
    }
}
