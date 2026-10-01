package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.runtime.AgentBackend
import dev.srimi.antigravitymobile.runtime.ToolOutcome
import org.json.JSONObject
import java.io.File

/** [drained] means the helper's readers finished: no further events can follow [eventCount]. */
data class CliWorkerState(val taskId: String, val state: String, val eventCount: Long,
    val exitCode: Int?, val cancellationUnconfirmed: Boolean, val drained: Boolean)
data class CliPage(val state: CliWorkerState, val events: List<JSONObject>)

/** Native runner controls task identity. Reconnection only observes; callers must never resend uncertain writes. */
interface CliBridgeEndpoint {
    suspend fun unavailable(backend: AgentBackend): ToolOutcome.RuntimeUnavailable?
    suspend fun prepare(snapshot: CliWorkspaceStore.Snapshot): String
    suspend fun start(taskId: String, backend: AgentBackend, sessionId: String?): Boolean
    suspend fun send(taskId: String, commandId: String, message: JSONObject): Boolean
    suspend fun observe(taskId: String, after: Long): CliPage
    suspend fun status(taskId: String): CliWorkerState
    suspend fun cancel(taskId: String): CliWorkerState
    suspend fun capture(taskId: String, destination: File): File
}
