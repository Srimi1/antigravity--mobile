package dev.srimi.antigravitymobile.runtime

import androidx.room.withTransaction
import dev.srimi.antigravitymobile.*
import java.util.UUID

/** Claim before a side effect; record before feeding a result back. Restore never executes. */
enum class LoopNext { Model, Tools, Done }

interface AgentJournal {
    val taskId: String
    suspend fun restore(call: AgentItem.ToolCall): RecordedExecution?
    suspend fun begin(call: AgentItem.ToolCall): ApprovalKey
    suspend fun prepared(key: ApprovalKey, preview: ToolPreview, category: ApprovalCategory?): ApprovalKey
    suspend fun claim(key: ApprovalKey): Boolean
    suspend fun finish(key: ApprovalKey, result: RecordedExecution)
    suspend fun checkpoint(items: List<AgentItem>, steps: Int, next: LoopNext)
}

/** Only for standalone loop use/tests. Phone runners always inject RoomAgentJournal. */
class TransientAgentJournal(override val taskId: String = UUID.randomUUID().toString()) : AgentJournal {
    private val keys = mutableMapOf<String, ApprovalKey>()
    private val results = mutableMapOf<String, RecordedExecution>()
    private val claimed = mutableSetOf<String>()
    override suspend fun restore(call: AgentItem.ToolCall) = results[call.callId]
    override suspend fun begin(call: AgentItem.ToolCall) = keys.getOrPut(call.callId) { ApprovalKey(taskId, UUID.randomUUID().toString(), call.callId) }
    override suspend fun prepared(key: ApprovalKey, preview: ToolPreview, category: ApprovalCategory?) = key.copy(buildId = preview.buildId).also { keys[key.toolCallId] = it }
    override suspend fun claim(key: ApprovalKey): Boolean = keys[key.toolCallId] == key && claimed.add(key.actionId)
    override suspend fun finish(key: ApprovalKey, result: RecordedExecution) { results.putIfAbsent(key.toolCallId, result) }
    override suspend fun checkpoint(items: List<AgentItem>, steps: Int, next: LoopNext) { RuntimeCodec.transcript(items) }
}

class RoomAgentJournal(private val database: SessionStore, override val taskId: String,
    private val clock: () -> Long = System::currentTimeMillis) : AgentJournal {
    private val dao = database.runtime()
    override suspend fun restore(call: AgentItem.ToolCall): RecordedExecution? = dao.action(taskId, call.callId)?.let { action ->
        check(action.tool == call.name && action.arguments == call.arguments) { "Tool call identity was reused with different arguments" }
        action.outcome?.let { RecordedExecution(RuntimeCodec.outcome(it), action.resultText.orEmpty(), action.status) }
    }
    override suspend fun begin(call: AgentItem.ToolCall): ApprovalKey {
        check(dao.action(taskId, call.callId) == null) { "An uncertain tool call cannot be replayed" }
        val at = clock()
        val key = ApprovalKey(taskId, UUID.randomUUID().toString(), call.callId)
        dao.createAction(RuntimeActionRecord(key.actionId, taskId, call.callId, call.name, call.arguments, call.name, "",
            null, null, "PREPARING", null, null, null, null, at, at))
        return key
    }
    override suspend fun prepared(key: ApprovalKey, preview: ToolPreview, category: ApprovalCategory?): ApprovalKey {
        database.withTransaction {
            val status = if (category == null) "READY" else "AWAITING_APPROVAL"
            check(dao.prepared(key.actionId, preview.summary, preview.detail, category?.name, preview.buildId,
                status, clock()) == 1) { "Tool preparation was interrupted" }
            val task = dao.task(taskId) ?: error("Task record missing")
            val action = dao.action(taskId, key.toolCallId) ?: error("Action record missing")
            database.conversations().saveAction(ActionRecord(action.id, task.conversationId, task.projectId, action.tool,
                action.summary, status, "", action.createdAt, clock()))
        }
        return key.copy(buildId = preview.buildId)
    }
    override suspend fun claim(key: ApprovalKey) = dao.claim(key.actionId, clock()) == 1
    override suspend fun finish(key: ApprovalKey, result: RecordedExecution) {
        database.withTransaction {
            if (dao.finish(key.actionId, RuntimeCodec.outcome(result.outcome), result.output, result.status, clock()) == 1) {
                val action = dao.action(taskId, key.toolCallId) ?: return@withTransaction
                val task = dao.task(taskId) ?: return@withTransaction
                database.conversations().saveAction(ActionRecord(action.id, task.conversationId, task.projectId, action.tool,
                    action.summary, result.status, result.output.take(1000), action.createdAt, clock()))
                database.conversations().saveMessage(MessageRecord("${action.id}:result", task.conversationId, "tool",
                    "${result.status}: ${action.summary}\n${result.output.take(8000)}", clock()))
            }
        }
    }
    override suspend fun checkpoint(items: List<AgentItem>, steps: Int, next: LoopNext) {
        database.withTransaction {
            val task = dao.task(taskId) ?: error("Task record missing")
            dao.checkpoint(taskId, RuntimeCodec.transcript(items), steps, next.name, clock())
            items.forEachIndexed { index, item ->
                if (index >= task.historySize && item is AgentItem.Assistant && item.text.isNotBlank()) {
                    val id = "$taskId:item:$index"
                    if (database.conversations().message(id) == null) database.conversations().saveMessage(MessageRecord(id,
                        task.conversationId, "assistant", item.text, clock()))
                }
            }
        }
    }
}
