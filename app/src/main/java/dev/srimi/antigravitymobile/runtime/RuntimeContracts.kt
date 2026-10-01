package dev.srimi.antigravitymobile.runtime

import kotlinx.coroutines.flow.Flow

/** Identity travels with the prompt, response, receipt and execution claim. Never answer by position. */
data class ApprovalKey(val taskId: String, val actionId: String, val toolCallId: String, val buildId: String? = null) {
    init { require(taskId.isNotBlank() && actionId.isNotBlank() && toolCallId.isNotBlank()) }
}

enum class ApprovalCategory { Edit, Build, Install, Command }

/** Only Declined represents an explicit user rejection. Cancellation and interruption are distinct. */
sealed interface ApprovalDecision {
    val key: ApprovalKey
    data class Approved(override val key: ApprovalKey, val allEdits: Boolean = false) : ApprovalDecision
    data class Declined(override val key: ApprovalKey) : ApprovalDecision
    data class Cancelled(override val key: ApprovalKey) : ApprovalDecision
    data class Interrupted(override val key: ApprovalKey) : ApprovalDecision
}

sealed interface ToolOutcome {
    data class Success(val data: String) : ToolOutcome
    data class Failed(val reason: String, val logRef: String? = null) : ToolOutcome
    data object Cancelled : ToolOutcome
    data object Interrupted : ToolOutcome
    data class RuntimeUnavailable(val reason: String) : ToolOutcome
}

enum class AgentBackend { Native, Codex, AntigravityCli }
enum class TaskPhase { Queued, Running, AwaitingApproval, Paused, Completed, Cancelled, Failed }

/** Selection is captured at start; retry never chooses another provider or backend. No credentials. */
data class TaskStart(
    val projectId: String,
    val conversationId: String?,
    val prompt: String,
    val providerId: String,
    val backend: AgentBackend = AgentBackend.Native,
)

sealed interface RunnerEvent {
    val taskId: String
    data class State(override val taskId: String, val phase: TaskPhase, val detail: String, val recoveryAction: String? = null) : RunnerEvent
    data class Text(override val taskId: String, val delta: String) : RunnerEvent
    data class Approval(override val taskId: String, val key: ApprovalKey, val category: ApprovalCategory,
        val tool: String, val summary: String, val detail: String) : RunnerEvent
    data class Receipt(override val taskId: String, val decision: ApprovalDecision) : RunnerEvent
    data class ToolResult(override val taskId: String, val actionId: String, val toolCallId: String,
        val outcome: ToolOutcome, val buildId: String? = null) : RunnerEvent
}

/** One active task across all runners. Implementations persist before acknowledging state changes. */
interface AgentTaskRunner {
    suspend fun start(request: TaskStart): String
    fun observe(taskId: String): Flow<RunnerEvent>
    suspend fun answerApproval(decision: ApprovalDecision): Boolean
    suspend fun cancel(taskId: String)
}
