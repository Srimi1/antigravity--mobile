package dev.srimi.antigravitymobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PendingApproval(val key: ApprovalKey, val category: ApprovalCategory, val tool: String, val summary: String, val detail: String)
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
    val backend: AgentBackend = AgentBackend.Native,
    val backendUnavailable: String? = null,
    val task: RuntimeTaskRecord? = null,
    val receipt: String? = null,
    val error: String? = null,
)

/** Screen adapter only. Rotation, navigation and onCleared never stop a service-owned task. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val services = application.container
    private val dao = services.database.conversations()
    private val mutable = MutableStateFlow(AgentState())
    val state = mutable.asStateFlow()
    private val conversationId = MutableStateFlow<String?>(null)
    init {
        viewModelScope.launch {
            services.selectedProjectId.flatMapLatest { id -> flow { emit(id?.let { services.projects.find(it) }) } }.collect { project ->
                if (project?.id != state.value.project?.id && state.value.task == null) conversationId.value = null
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
        viewModelScope.launch {
            services.ready.await()
            services.tasks.view.collect { view ->
                view.task?.let { task ->
                    if (services.selectedProjectId.value != task.projectId) services.selectProject(task.projectId)
                    conversationId.value = task.conversationId
                }
                val approval = view.approval?.let { action -> PendingApproval(action.key(), ApprovalCategory.valueOf(action.category!!),
                    action.tool, action.summary, action.preview) }
                val receipt = view.receipt?.let { action ->
                    "Approval receipt: ${action.decision?.lowercase()} · action ${action.id.take(8)}" +
                        (action.buildId?.let { " · build ${it.take(8)}" } ?: "") + "\nRecorded action state: ${action.status}"
                }
                mutable.update { it.copy(task = view.task, approval = approval, receipt = receipt, streaming = view.streaming,
                    backend = view.task?.backend?.let(AgentBackend::valueOf) ?: services.agentBackend,
                    running = view.task?.status in setOf(TaskPhase.Queued.name, TaskPhase.Running.name, TaskPhase.AwaitingApproval.name),
                    autoApprove = view.task?.autoApproveEdits == true) }
            }
        }
        refreshAccount()
    }
    fun refreshAccount() {
        viewModelScope.launch {
            val backend = state.value.task?.backend?.let(AgentBackend::valueOf) ?: services.agentBackend
            val account = withContext(Dispatchers.IO) { services.agentAccount() }
            val unavailable = withContext(Dispatchers.IO) { services.tasks.unavailable(backend)?.reason }
            mutable.update { if ((it.task?.backend ?: services.agentBackend.name) == backend.name)
                it.copy(account = account, backend = backend, backendUnavailable = unavailable) else it }
        }
    }
    fun selectBackend(backend: AgentBackend) {
        if (state.value.task != null || state.value.running) return
        services.agentBackend = backend
        mutable.update { it.copy(backend = backend, backendUnavailable = if (backend == AgentBackend.Native) null else "Checking CLI availability…", error = null) }
        refreshAccount()
    }
    fun newConversation() { if (state.value.task == null) conversationId.value = null }
    fun openConversation(id: String) { if (state.value.task == null) conversationId.value = id }
    fun deleteConversation(id: String) {
        if (state.value.task?.conversationId == id) return
        viewModelScope.launch {
            services.database.withTransaction {
                val runtime = services.database.runtime()
                if (runtime.active()?.conversationId == id) return@withTransaction
                runtime.deleteActionsForConversation(id); runtime.deleteTasksForConversation(id)
                dao.deleteMessages(id); dao.deleteActions(id); dao.delete(id)
            }
            if (conversationId.value == id) conversationId.value = null
        }
    }
    fun send(text: String) {
        val prompt = text.trim()
        val project = state.value.project ?: return
        if (prompt.isEmpty() || state.value.task != null || state.value.running) return
        mutable.update { it.copy(running = true, error = null) }
        services.scope.launch {
            try {
                val id = services.tasks.start(TaskStart(project.id, conversationId.value, prompt, services.agentProvider.name, services.agentBackend))
                conversationId.value = services.database.runtime().task(id)?.conversationId
            } catch (error: Exception) { mutable.update { it.copy(running = false, error = friendly(error)) } }
        }
    }
    fun answerApproval(decision: ApprovalDecision) {
        services.scope.launch { services.tasks.answerApproval(decision) }
    }
    fun retry() { state.value.task?.let { task -> services.scope.launch { services.tasks.retry(task.id) } } }
    fun stop() { state.value.task?.let { task -> services.scope.launch { services.tasks.cancel(task.id) } } }
}
