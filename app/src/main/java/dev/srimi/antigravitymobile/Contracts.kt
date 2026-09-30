package dev.srimi.antigravitymobile

import kotlinx.coroutines.flow.Flow

enum class CheckStatus { PASSED, FAILED, BLOCKED, UNVERIFIED, RUNNING, CANCELLED, INTERRUPTED }
data class ProviderCapabilities(val subscription: CheckStatus, val streaming: Boolean, val tools: Boolean, val reason: String)
sealed interface ProviderEvent {
    data class Text(val delta: String) : ProviderEvent
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
data class CommandResult(val exitCode: Int, val output: String, val durationMs: Long)
interface ExecutionService {
    suspend fun execute(argument: String, onOutput: (String) -> Unit = {}): CommandResult
    fun cancel()
}
interface BuildService {
    fun readiness(): ProviderCapabilities
}
data class FileChange(val path: String, val before: String?, val after: String?)
