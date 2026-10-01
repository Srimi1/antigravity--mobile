package dev.srimi.antigravitymobile.runtime

import android.content.Context
import androidx.room.withTransaction
import dev.srimi.antigravitymobile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** UI projection. Durable records own identity and state; only partial streaming text is transient. */
data class RuntimeView(val task: RuntimeTaskRecord? = null, val approval: RuntimeActionRecord? = null,
    val receipt: RuntimeActionRecord? = null, val streaming: String = "")
data class RuntimePause(val reason: String, val recovery: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAgentTaskRunner(
    private val services: AppContainer,
    private val context: Context,
    private val modelFor: (String) -> AgentModel = { services.agentModel(ProviderId.valueOf(it)) },
    private val buildFor: (ProjectRecord, String) -> BuildRunner = { project, task -> PhoneBuildRunner(services, project, context, task) },
    private val dispatch: (String) -> Unit = { AgentTaskService.execute(context, it) },
    private val failureDescription: (Throwable) -> RuntimePause = ::providerPause,
) : AgentTaskRunner {
    private val database = services.database
    private val dao = database.runtime()
    private val mutex = Mutex()
    private var execution: Job? = null
    private var executingId: String? = null
    private var activeModel: AgentModel? = null
    private val clock = AtomicLong()
    private fun now() = clock.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
    private val stream = MutableStateFlow<Pair<String?, String>>(null to "")
    private val textEvents = MutableSharedFlow<RunnerEvent.Text>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val view: StateFlow<RuntimeView> = combine(
        dao.observeActive().flatMapLatest { task ->
            if (task == null) flowOf(RuntimeView()) else dao.observeActions(task.id).map { actions ->
                RuntimeView(task, actions.firstOrNull { it.status == "AWAITING_APPROVAL" && it.decision == null },
                    actions.lastOrNull { it.decision != null })
            }
        }, stream,
    ) { state, live -> state.copy(streaming = if (state.task?.id == live.first) live.second else "") }
        .stateIn(services.scope, SharingStarted.Eagerly, RuntimeView())

    override suspend fun start(request: TaskStart): String {
        services.ready.await()
        require(request.backend == AgentBackend.Native) { "CLI runner is not enabled yet" }
        require(request.prompt.isNotBlank() && request.prompt.length <= 100_000)
        val previous = mutex.withLock {
            check(dao.active() == null) { "Finish, retry or stop the current task first" }
            execution?.takeUnless { it.isCompleted }
        }
        previous?.join() // Finish old conversation cleanup before reserving the next task.
        val task = mutex.withLock {
            check(dao.active() == null) { "Finish, retry or stop the current task first" }
            val project = services.projects.find(request.projectId) ?: error("Project not found")
            val conversations = database.conversations()
            val existing = request.conversationId?.let { conversations.find(it) ?: error("Conversation not found") }
            require(existing == null || existing.projectId == project.id) { "Conversation belongs to another project" }
            val at = maxOf(now(), existing?.let { conversations.messages(it.id).maxOfOrNull { row -> row.createdAt }?.plus(1) } ?: 0)
            clock.updateAndGet { maxOf(it, at) }
            val conversation = existing ?: ConversationRecord(UUID.randomUUID().toString(), project.id,
                request.prompt.lineSequence().first().take(60), request.providerId, "RUNNING", at, at)
            val selection = services.agentSelection(request.providerId)
            val history = history(conversation, request.providerId, selection)
            val id = UUID.randomUUID().toString()
            val record = RuntimeTaskRecord(id, project.id, conversation.id, request.providerId, request.backend.name,
                request.prompt, TaskPhase.Queued.name, "Starting foreground task", null,
                RuntimeCodec.transcript(history + AgentItem.User(request.prompt)), 0, LoopNext.Model.name, history.size,
                null, false, 1, at, at, selection)
            database.withTransaction {
                conversations.save(conversation.copy(provider = request.providerId, status = "RUNNING", updatedAt = at))
                conversations.saveMessage(MessageRecord("$id:prompt", conversation.id, "user", request.prompt, at))
                dao.createTask(record)
            }
            record
        }
        dispatchSafely(task.id)
        return task.id
    }
    private suspend fun history(conversation: ConversationRecord, provider: String, selection: String?): List<AgentItem> {
        val last = dao.forConversation(conversation.id).lastOrNull()
        if (last != null && last.providerId == provider && last.providerSelection == selection && last.backend == AgentBackend.Native.name) {
            val items = RuntimeCodec.items(last.transcript).toMutableList()
            val results = items.filterIsInstance<AgentItem.ToolResult>().map { it.callId }.toSet()
            items.filterIsInstance<AgentItem.ToolCall>().filter { it.callId !in results }.forEach { call ->
                val action = dao.action(last.id, call.callId)
                items += AgentItem.ToolResult(call.callId, action?.resultText ?: ToolOutcome.Interrupted.text())
            }
            return items
        }
        return database.conversations().messages(conversation.id).mapNotNull { message -> when (message.role) {
            "user" -> AgentItem.User(message.content)
            "assistant" -> AgentItem.Assistant(message.content)
            else -> null
        } }
    }
    private suspend fun dispatchSafely(id: String) {
        try { withContext(Dispatchers.Main) { dispatch(id) } }
        catch (error: Exception) { pause(id, RuntimePause("foreground task service unavailable", "Return to the app, then retry this provider.")) }
    }
    suspend fun retry(id: String) {
        services.ready.await()
        val previous = mutex.withLock {
            val task = dao.task(id) ?: return
            if (task.activeSlot != 1 || task.status != TaskPhase.Paused.name) return
            if (executingId == id) execution else null
        }
        previous?.join()
        mutex.withLock {
            val task = dao.task(id) ?: return
            if (task.activeSlot != 1 || task.status != TaskPhase.Paused.name) return
            check(dao.phase(id, TaskPhase.Queued.name, "Checking recorded actions before retry", null, 1, now()) == 1)
        }
        dispatchSafely(id)
    }
    /** Service entry only. Screens never own or launch the execution coroutine. */
    suspend fun launchFromService(id: String, scope: CoroutineScope) {
        services.ready.await()
        val previous = mutex.withLock {
            if (execution?.isCompleted == false && executingId == id) return
            execution?.takeUnless { it.isCompleted }
        }
        previous?.join()
        mutex.withLock {
            if (execution?.isCompleted == false) return
            val task = dao.task(id) ?: return
            if (task.activeSlot != 1 || task.status != TaskPhase.Queued.name) return
            executingId = id
            execution = scope.launch { run(id) }
        }
    }
    override fun observe(taskId: String): Flow<RunnerEvent> = merge(recordedEvents(taskId), textEvents.filter { it.taskId == taskId })
    private fun recordedEvents(taskId: String): Flow<RunnerEvent> = dao.observeTask(taskId).filterNotNull().flatMapLatest { task ->
        dao.observeActions(taskId).transform { actions ->
            emit(RunnerEvent.State(taskId, TaskPhase.valueOf(task.status), task.detail, task.recoveryAction))
            actions.forEach { action ->
                val key = action.key()
                if (action.status == "AWAITING_APPROVAL" && action.decision == null) emit(RunnerEvent.Approval(taskId, key,
                    ApprovalCategory.valueOf(action.category!!), action.tool, action.summary, action.preview))
                action.decision?.let { emit(RunnerEvent.Receipt(taskId, decision(key, it, action.allEdits))) }
                action.outcome?.let { emit(RunnerEvent.ToolResult(taskId, action.id, action.toolCallId, RuntimeCodec.outcome(it), action.buildId)) }
            }
        }
    }
    override suspend fun answerApproval(decision: ApprovalDecision): Boolean {
        val key = decision.key
        val accepted = dao.answer(key.taskId, key.actionId, key.toolCallId, key.buildId, decision.name(),
            (decision as? ApprovalDecision.Approved)?.allEdits == true, now())
        if (accepted) dao.action(key.taskId, key.toolCallId)?.let { receipt(it) }
        return accepted
    }
    override suspend fun cancel(taskId: String) = withContext(NonCancellable) {
        services.ready.await()
        val job = mutex.withLock {
            val task = dao.task(taskId) ?: return@withContext
            if (task.activeSlot != 1) return@withContext
            dao.phase(taskId, TaskPhase.Cancelled.name, "Stopped; no user decline recorded", null, 1, now())
            dao.closePending(taskId, "CANCELLED", now())
            if (executingId == taskId) execution else null
        }
        job?.cancel() // Mark cancellation before HTTP cancel can throw an IOException.
        activeModel?.cancel()
        cancelRecordedBuilds(taskId)
        job?.join()
        withContext(NonCancellable) {
            settleActions(taskId, stopping = true)
            finishChanges(taskId)
            dao.releaseCancelled(taskId)
            conversationState(taskId, "CANCELLED")
            reconcileBuilds()
        }
    }
    /** Startup restores a paused checkpoint. It never starts a provider request or re-dispatches a tool. */
    suspend fun recover() {
        dao.pauseAfterDeath(now())
        dao.active()?.let { task ->
            val stopped = task.status == TaskPhase.Cancelled.name
            if (stopped) cancelRecordedBuilds(task.id)
            dao.closePending(task.id, if (stopped) "CANCELLED" else "INTERRUPTED", now())
            settleActions(task.id, stopping = stopped, waitForBuilds = false)
            if (stopped) dao.releaseCancelled(task.id)
            conversationState(task.id, if (stopped) "CANCELLED" else "PAUSED")
        }
        reconcileBuilds()
    }
    private suspend fun cancelRecordedBuilds(taskId: String) {
        dao.actions(taskId).filter { it.tool == "build_project" && it.buildId != null && it.status in setOf("RUNNING", "INTERRUPTED") }
            .forEach { services.builds.cancel(it.buildId!!) }
    }
    private suspend fun run(id: String) {
        val task = dao.task(id) ?: return
        if (dao.phase(id, TaskPhase.Running.name, "Agent working", null, 1, now()) != 1) return
        stream.value = id to ""
        val journal = RoomAgentJournal(database, id, ::now)
        try {
            settleActions(id, stopping = false, waitForBuilds = true)
            val current = dao.task(id) ?: error("Task record missing")
            val project = services.projects.find(current.projectId) ?: error("Project not found")
            val runner = buildFor(project, id)
            val fileTools = WorkspaceTools(services.workspace(project), services.changes, {
                val record = dao.task(id) ?: error("Task record missing")
                val existing = record.changeSetId?.let { database.changes().findSet(it) }
                if (existing?.status == "OPEN") existing.id else services.changes.open(project.id, current.conversationId, current.prompt).id
                    .also { dao.changeSet(id, it) }
            }, {
                val directory = services.projects.directory(project)
                if (services.git.isRepository(directory)) services.git.status(directory).let { value ->
                    value.summary + "\nmodified: ${value.modified.sorted().joinToString()}\nuntracked: ${value.untracked.sorted().joinToString()}"
                } else "This project is not a Git repository"
            })
            val gate = ApprovalGate { key, _, preview ->
                check(dao.phase(id, TaskPhase.AwaitingApproval.name, "Waiting for approval: ${preview.summary}", null, 1, now()) == 1)
                val action = dao.action(id, key.toolCallId) ?: error("Approval record missing")
                if (action.category == ApprovalCategory.Edit.name && dao.task(id)?.autoApproveEdits == true)
                    dao.answer(id, key.actionId, key.toolCallId, key.buildId, "APPROVED", false, now())
                val resolved = dao.observeActions(id).map { list -> list.first { it.id == key.actionId } }.first { it.decision != null }
                receipt(resolved)
                dao.phase(id, TaskPhase.Running.name, "Approval ${resolved.decision?.lowercase()}; observing execution", null, 1, now())
                decision(key, resolved.decision!!, resolved.allEdits)
            }
            val listener = object : AgentListener {
                override suspend fun onTextDelta(delta: String) {
                    stream.update { id to (it.second + delta).takeLast(64_000) }
                    textEvents.emit(RunnerEvent.Text(id, delta))
                }
                override suspend fun onAssistantMessage(text: String) { stream.value = id to "" }
                override suspend fun onNotice(text: String) { note(current.conversationId, "notice", text) }
            }
            val delegate = modelFor(current.providerId)
            val model = object : AgentModel {
                override val providerId = delegate.providerId
                override fun cancel() = delegate.cancel()
                override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow {
                    if (current.providerSelection != services.agentSelection(current.providerId))
                        throw ProviderSelectionChanged()
                    emitAll(delegate.streamAgentTurn(request))
                }
            }
            activeModel = model
            val recorded = runner.recorded()?.report()?.take(8000).orEmpty()
            val instructions = AgentOrchestrator.INSTRUCTIONS + if (recorded.isEmpty()) "" else
                "\nActual recorded build for this project (use build_result/read_build_log for details):\n$recorded"
            AgentOrchestrator(model, AgentBuildTools(fileTools, runner), gate, listener, journal = journal)
                .resume(instructions, RuntimeCodec.items(current.transcript), current.completedSteps, LoopNext.valueOf(current.nextStep))
            finishChanges(id)
            dao.phase(id, TaskPhase.Completed.name, "Task completed; review recorded outcomes and Changes", null, null, now())
            withContext(NonCancellable) { conversationState(id, "IDLE") }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val stopped = dao.task(id)?.status == TaskPhase.Cancelled.name
                settleActions(id, stopping = stopped, waitForBuilds = false)
                finishChanges(id)
                if (!stopped) pause(id, RuntimePause("task service stopped", "Review recorded outcomes, then retry this provider."))
                reconcileBuilds()
            }
        } catch (error: ModelRequestFailure) {
            pause(id, failureDescription(error.cause ?: error))
            finishChanges(id)
        } catch (_: Exception) {
            pause(id, RuntimePause("task execution interrupted", "Review Changes and recorded build outcomes before retrying this provider."))
            finishChanges(id)
        } finally {
            activeModel = null; stream.value = null to ""
        }
    }
    private suspend fun settleActions(id: String, stopping: Boolean, waitForBuilds: Boolean = false) {
        val task = dao.task(id) ?: return
        val journal = RoomAgentJournal(database, id, ::now)
        val project = services.projects.find(task.projectId)
        // A complete turn may have been saved just before death, without reaching begin(). Resolve those calls
        // as interrupted as well: retry resumes the unfinished model request, not old unexecuted commands.
        val items = RuntimeCodec.items(task.transcript)
        val resolved = items.filterIsInstance<AgentItem.ToolResult>().map { it.callId }.toSet()
        for (call in items.filterIsInstance<AgentItem.ToolCall>().filter { it.callId !in resolved }) {
            if (dao.action(id, call.callId) == null) {
                val key = journal.begin(call)
                journal.finish(key, RecordedExecution(if (stopping) ToolOutcome.Cancelled else ToolOutcome.Interrupted))
            }
        }
        for (action in dao.actions(id).filter { it.outcome == null }) {
            if (action.decision != null) receipt(action)
            if (action.status == "AWAITING_APPROVAL" && action.decision == null && !stopping) continue
            if (action.status == "RUNNING" && action.tool == "build_project" && action.buildId != null && project != null) {
                if (!waitForBuilds) continue
                val outcome = buildFor(project, id).awaitExisting(action.buildId).toolOutcome()
                journal.finish(action.key(), RecordedExecution(outcome)); continue
            }
            if (action.decision == "DECLINED") {
                action.buildId?.takeIf { action.tool == "build_project" }?.let { services.builds.resolvePending(it, "DECLINED") }
                journal.finish(action.key(), RecordedExecution(ToolOutcome.Cancelled,
                    "The user explicitly declined this action. Do not retry it unless the user asks.", "DECLINED"))
                continue
            }
            val outcome = if ((stopping || action.decision == "CANCELLED") && action.status != "RUNNING") ToolOutcome.Cancelled else ToolOutcome.Interrupted
            if (action.buildId != null && action.tool == "build_project") services.builds.resolvePending(action.buildId,
                if (stopping) "CANCELLED" else "INTERRUPTED")
            journal.finish(action.key(), RecordedExecution(outcome))
        }
    }
    private val buildObservations = mutableSetOf<String>()
    private suspend fun reconcileBuilds() {
        for (action in dao.unresolvedBuilds()) {
            if (!synchronized(buildObservations) { buildObservations.add(action.id) }) continue
            services.scope.launch {
                try {
                    val task = dao.task(action.taskId) ?: return@launch
                    val project = services.projects.find(task.projectId) ?: return@launch
                    val outcome = buildFor(project, task.id).awaitExisting(action.buildId!!).toolOutcome()
                    RoomAgentJournal(database, task.id, ::now).finish(action.key(), RecordedExecution(outcome))
                } catch (_: Exception) { /* Remain unconfirmed; an observer never dispatches a build. */ }
                finally { synchronized(buildObservations) { buildObservations.remove(action.id) } }
            }
        }
    }
    private suspend fun receipt(action: RuntimeActionRecord) {
        val task = dao.task(action.taskId) ?: return
        database.conversations().saveMessage(MessageRecord("${action.id}:approval", task.conversationId, "notice",
            "Approval receipt: ${action.decision}${if (action.allEdits) " (all edits only)" else ""}. ${action.summary} · action ${action.id.take(8)}" +
                (action.buildId?.let { " · build ${it.take(8)}" } ?: ""), action.decidedAt ?: action.updatedAt))
    }
    private suspend fun finishChanges(id: String) {
        dao.task(id)?.changeSetId?.let { set -> services.changes.finish(set); dao.changeSet(id, null) }
    }
    private suspend fun pause(id: String, reason: RuntimePause) {
        if (dao.phase(id, TaskPhase.Paused.name, "Paused: ${reason.reason}", reason.recovery, 1, now()) == 1) {
            conversationState(id, "PAUSED")
            dao.task(id)?.let { note(it.conversationId, "notice", "Paused: ${reason.reason}" + (reason.recovery?.let { action -> "\n$action" } ?: "")) }
        }
    }
    private suspend fun conversationState(id: String, status: String) {
        val task = dao.task(id) ?: return
        val conversation = database.conversations().find(task.conversationId) ?: return
        database.conversations().save(conversation.copy(status = status, updatedAt = now()))
    }
    private suspend fun note(conversationId: String, role: String, text: String) {
        database.conversations().saveMessage(MessageRecord(UUID.randomUUID().toString(), conversationId, role, text, now()))
    }
}

fun RuntimeActionRecord.key() = ApprovalKey(taskId, id, toolCallId, buildId)
fun ApprovalDecision.name(): String = when (this) {
    is ApprovalDecision.Approved -> "APPROVED"
    is ApprovalDecision.Declined -> "DECLINED"
    is ApprovalDecision.Cancelled -> "CANCELLED"
    is ApprovalDecision.Interrupted -> "INTERRUPTED"
}
fun decision(key: ApprovalKey, value: String, allEdits: Boolean = false): ApprovalDecision = when (value) {
    "APPROVED" -> ApprovalDecision.Approved(key, allEdits)
    "DECLINED" -> ApprovalDecision.Declined(key)
    "CANCELLED" -> ApprovalDecision.Cancelled(key)
    "INTERRUPTED" -> ApprovalDecision.Interrupted(key)
    else -> error("Invalid approval receipt")
}
