package dev.srimi.antigravitymobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class PendingApproval(val tool: String, val summary: String, val detail: String)
data class AgentState(
    val project: ProjectRecord? = null,
    val conversations: List<ConversationRecord> = emptyList(),
    val conversationId: String? = null,
    val messages: List<MessageRecord> = emptyList(),
    val actions: List<ActionRecord> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val approval: PendingApproval? = null,
    val autoApprove: Boolean = false,
    val account: AccountState? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.container
    private val dao = services.database.conversations()
    private val mutable = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = mutable.asStateFlow()
    private val conversationId = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private var approvalAnswer: CompletableDeferred<Boolean>? = null
    private val clock = AtomicLong(0)
    /** Strictly increasing timestamps keep message order stable within one millisecond. */
    private fun now(): Long = clock.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }

    init {
        viewModelScope.launch {
            services.selectedProjectId.flatMapLatest { id ->
                flow { emit(id?.let { services.projects.find(it) }) }
            }.collect { project ->
                if (project?.id != state.value.project?.id && !state.value.running) conversationId.value = null
                mutable.update { it.copy(project = project) }
            }
        }
        viewModelScope.launch {
            services.selectedProjectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeForProject(id) }
                .collect { list -> mutable.update { it.copy(conversations = list) } }
        }
        viewModelScope.launch {
            conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeMessages(id) }
                .collect { list -> mutable.update { it.copy(messages = list) } }
        }
        viewModelScope.launch {
            conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeActions(id) }
                .collect { list -> mutable.update { it.copy(actions = list) } }
        }
        viewModelScope.launch { conversationId.collect { id -> mutable.update { it.copy(conversationId = id) } } }
        refreshAccount()
    }

    fun refreshAccount() {
        viewModelScope.launch { mutable.update { it.copy(account = withContext(Dispatchers.IO) { services.chatgpt.accountState() }) } }
    }
    fun newConversation() { if (!state.value.running) conversationId.value = null }
    fun openConversation(id: String) { if (!state.value.running) conversationId.value = id }
    fun deleteConversation(id: String) {
        if (state.value.running && id == conversationId.value) return
        viewModelScope.launch {
            dao.deleteMessages(id); dao.deleteActions(id); dao.delete(id)
            if (conversationId.value == id) conversationId.value = null
        }
    }
    fun setAutoApprove(value: Boolean) = mutable.update { it.copy(autoApprove = value) }

    private suspend fun note(conversation: String, role: String, content: String) =
        dao.saveMessage(MessageRecord(UUID.randomUUID().toString(), conversation, role, content, now()))

    fun send(text: String) {
        val prompt = text.trim()
        val project = state.value.project ?: return
        if (prompt.isEmpty() || state.value.running) return
        mutable.update { it.copy(running = true, streaming = "", autoApprove = false) }
        job = viewModelScope.launch {
            services.ready.await()
            val existing = conversationId.value?.let { dao.find(it) }
            val conversation = (existing ?: ConversationRecord(UUID.randomUUID().toString(), project.id, prompt.lineSequence().first().take(60),
                ProviderId.CHATGPT.name, "IDLE", now(), now())).copy(status = "RUNNING", updatedAt = now())
            dao.save(conversation)
            conversationId.value = conversation.id
            val history = dao.messages(conversation.id).mapNotNull {
                when (it.role) { "user" -> AgentItem.User(it.content); "assistant" -> AgentItem.Assistant(it.content); else -> null }
            }
            note(conversation.id, "user", prompt)
            val workspace = services.workspace(project)
            val dir = services.projects.directory(project)
            var changeSet: String? = null
            val actionIds = mutableMapOf<String, String>()
            val tools = WorkspaceTools(workspace, services.changes, {
                changeSet ?: services.changes.open(project.id, conversation.id, prompt).id.also { changeSet = it }
            }, { if (services.git.isRepository(dir)) services.git.status(dir).let { s ->
                s.summary + "\n" + listOf("staged" to s.added + s.changed + s.removed, "modified" to s.modified,
                    "untracked" to s.untracked, "deleted" to s.missing).filter { it.second.isNotEmpty() }
                    .joinToString("\n") { "${it.first}: ${it.second.sorted().joinToString()}" } } else "This project is not a Git repository" })
            val gate = ApprovalGate { call, preview ->
                val actionId = UUID.randomUUID().toString()
                actionIds[call.callId] = actionId
                dao.saveAction(ActionRecord(actionId, conversation.id, project.id, call.name, preview.summary, "AWAITING_APPROVAL", "", now(), now()))
                val approved = if (state.value.autoApprove) true else {
                    val answer = CompletableDeferred<Boolean>()
                    approvalAnswer = answer
                    mutable.update { it.copy(approval = PendingApproval(call.name, preview.summary, preview.detail)) }
                    try { answer.await() } finally { approvalAnswer = null; mutable.update { it.copy(approval = null) } }
                }
                if (approved) dao.saveAction(ActionRecord(actionId, conversation.id, project.id, call.name, preview.summary, "RUNNING", "", now(), now()))
                approved
            }
            val listener = object : AgentListener {
                override suspend fun onTextDelta(delta: String) = mutable.update { it.copy(streaming = (it.streaming + delta).takeLast(64_000)) }
                override suspend fun onAssistantMessage(text: String) {
                    note(conversation.id, "assistant", text)
                    mutable.update { it.copy(streaming = "") }
                }
                override suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, status: String, output: String) {
                    val id = actionIds[call.callId] ?: UUID.randomUUID().toString()
                    val detail = if (status == "COMPLETED" && call.name !in setOf("write_file", "delete_file")) "" else output.take(300)
                    dao.saveAction(ActionRecord(id, conversation.id, project.id, call.name, preview.summary, status, detail, now(), now()))
                    note(conversation.id, "tool", "${status.lowercase().replaceFirstChar { it.uppercase() }}: ${preview.summary}" +
                        if (status == "FAILED") " (${output.removePrefix("Error: ").take(160)})" else "")
                }
                override suspend fun onNotice(text: String) = note(conversation.id, "notice", text)
            }
            try {
                AgentOrchestrator(services.chatgpt, tools, gate, listener).run(AgentOrchestrator.INSTRUCTIONS, history, prompt)
            } catch (error: Exception) {
                // Stopping cancels the HTTP call too, which can surface as an IOException before cancellation does.
                withContext(NonCancellable) {
                    if (error is CancellationException || !isActive) note(conversation.id, "notice", "Stopped. Nothing was retried.")
                    else note(conversation.id, "error", "${friendly(error)}. Nothing was retried; no fallback provider was used.")
                }
            } finally {
                withContext(NonCancellable) {
                    changeSet?.let {
                        services.changes.finish(it)
                        note(conversation.id, "notice", "File edits from this task are waiting in Changes for review.")
                    }
                    dao.save(conversation.copy(status = "IDLE", updatedAt = now()))
                    mutable.update { it.copy(running = false, streaming = "", approval = null) }
                    refreshAccount()
                }
            }
        }
    }

    fun answerApproval(approved: Boolean, allForTask: Boolean = false) {
        if (allForTask && approved) mutable.update { it.copy(autoApprove = true) }
        approvalAnswer?.complete(approved)
    }

    fun stop() {
        approvalAnswer?.complete(false)
        services.chatgpt.cancel()
        job?.cancel()
    }

    override fun onCleared() { stop() }
}
