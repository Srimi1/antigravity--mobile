package dev.srimi.antigravitymobile

import kotlinx.coroutines.flow.Flow

enum class CheckStatus { PASSED, FAILED, BLOCKED, UNVERIFIED, RUNNING, CANCELLED, INTERRUPTED }
data class ProviderCapabilities(val subscription: CheckStatus, val streaming: Boolean, val tools: Boolean, val reason: String)

/** Provider-neutral transcript items for the common agent loop. */
sealed interface AgentItem {
    data class User(val text: String) : AgentItem
    data class Assistant(val text: String) : AgentItem
    data class ToolCall(val callId: String, val name: String, val arguments: String) : AgentItem
    data class ToolResult(val callId: String, val output: String) : AgentItem
    /** Provider state that must be replayed verbatim to the same provider (for example encrypted reasoning). */
    data class Opaque(val provider: String, val json: String) : AgentItem
}
data class ToolSpec(val name: String, val description: String, val parametersJson: String)
data class AgentRequest(val instructions: String, val input: List<AgentItem>, val tools: List<ToolSpec>)

sealed interface ProviderEvent {
    data class Text(val delta: String) : ProviderEvent
    /** A finished output item, in provider order. */
    data class Item(val item: AgentItem) : ProviderEvent
    data object Completed : ProviderEvent
}
interface ProviderAdapter {
    val capabilities: ProviderCapabilities
    suspend fun authenticate(openBrowser: (String) -> Unit)
    fun streamTurn(prompt: String): Flow<ProviderEvent>
    fun cancel()
    suspend fun renewCredentials()
    suspend fun disconnect(): Boolean
}
/** A model that can take part in the tool loop. Implementations must never fabricate output. */
interface AgentModel {
    val providerId: String
    fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent>
    fun cancel()
}
data class CommandResult(val exitCode: Int, val output: String, val durationMs: Long)
interface ExecutionService {
    suspend fun execute(argument: String, onOutput: (String) -> Unit = {}): CommandResult
    fun cancel()
}
interface BuildService {
    fun readiness(): ProviderCapabilities
}
data class FileChange(val path: String, val before: String?, val after: String?)
