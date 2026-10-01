package dev.srimi.antigravitymobile.bridge

import android.content.Context
import androidx.room.withTransaction
import dev.srimi.antigravitymobile.*
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** CLI owns its model/tool loop. Native code journals transport claims, approvals and returned changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class CliAgentTaskRunner(
    private val services: AppContainer,
    private val context: Context,
    private val bridge: CliBridgeEndpoint = DisabledCliBridge,
    private val workspaces: CliWorkspaceStore = CliWorkspaceStore(File(context.noBackupFilesDir, "cli-snapshots")),
    private val events: CliEventStore = CliEventStore(File(context.noBackupFilesDir, "cli-events")),
    private val resultRoot: File = File(context.noBackupFilesDir, "cli-results"),
    private val dispatch: (String) -> Unit = { AgentTaskService.execute(context, it) },
    private val livenessInterval: Long = 2_000,
    private val cleanupTimeout: Long = 30_000,
) : TaskRunnerLifecycle {
    private val database = services.database
    private val dao = database.runtime()
    private val mutex = Mutex()
    private var execution: Job? = null
    private var executingId: String? = null
    private val clock = AtomicLong()
    private fun now() = clock.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
    private val streaming = MutableStateFlow<Pair<String?, String>>(null to "")
    private val textEvents = MutableSharedFlow<RunnerEvent.Text>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val view: StateFlow<RuntimeView> = combine(
        dao.observeActive().flatMapLatest { task -> if (task == null) flowOf(RuntimeView()) else
            dao.observeActions(task.id).map { actions -> RuntimeView(task,
                actions.firstOrNull { it.status == "AWAITING_APPROVAL" && it.decision == null },
                actions.lastOrNull { it.decision != null }) } }, streaming,
    ) { state, text -> state.copy(streaming = if (state.task?.id == text.first) text.second else "") }
        .stateIn(services.scope, SharingStarted.Eagerly, RuntimeView())
    private fun isCli(task: RuntimeTaskRecord) = task.backend in setOf(AgentBackend.Codex.name, AgentBackend.AntigravityCli.name)
    private val activeStates = setOf("STARTING", "RUNNING", "CANCEL_REQUESTED")
    /** The slot may be released only when the helper proves the CLI stopped and no further event can follow. */
    private fun CliWorkerState.stopped() = state in setOf("NOT_FOUND", "EXITED", "CANCELLED", "INTERRUPTED") &&
        !cancellationUnconfirmed && drained
    suspend fun unavailable(backend: AgentBackend) = bridge.unavailable(backend)
    private fun checkpoint(task: RuntimeTaskRecord) = BridgeSecurity.json(task.transcript)
    private suspend fun save(id: String, data: JSONObject, count: Int, stage: String) =
        dao.checkpoint(id, data.toString(), count, stage, now())

    override suspend fun start(request: TaskStart): String {
        services.ready.await()
        require(request.backend != AgentBackend.Native && request.prompt.isNotBlank() && request.prompt.length <= 100_000)
        check(dao.active() == null) { "Finish, retry or stop the current task first" }
        awaitIdle()
        val task = mutex.withLock {
            check(dao.active() == null) { "Finish, retry or stop the current task first" }
            val project = services.projects.find(request.projectId) ?: error("Project not found")
            val conversations = database.conversations()
            val existing = request.conversationId?.let { conversations.find(it) ?: error("Conversation not found") }
            require(existing == null || existing.projectId == project.id)
            val at = maxOf(now(), existing?.let { conversations.messages(it.id).maxOfOrNull { row -> row.createdAt }?.plus(1) } ?: 0)
            clock.updateAndGet { maxOf(it, at) }
            val provider = request.backend.name
            val conversation = existing ?: ConversationRecord(UUID.randomUUID().toString(), project.id,
                request.prompt.lineSequence().first().take(60), provider, "RUNNING", at, at)
            val last = dao.forConversation(conversation.id).lastOrNull()?.takeIf { it.backend == request.backend.name }
            val session = last?.let { checkpoint(it).nullableText("session") }
            val data = JSONObject().put("v", 1).put("session", session ?: JSONObject.NULL)
                .put("resumeSession", session ?: JSONObject.NULL).put("cwd", JSONObject.NULL)
            val record = RuntimeTaskRecord(UUID.randomUUID().toString(), project.id, conversation.id, provider,
                request.backend.name, request.prompt, TaskPhase.Queued.name, "Starting local CLI task", null,
                data.toString(), 0, "Snapshot", 0, null, false, 1, at, at)
            database.withTransaction {
                conversations.save(conversation.copy(provider = provider, status = "RUNNING", updatedAt = at))
                conversations.saveMessage(MessageRecord("${record.id}:prompt", conversation.id, "user", request.prompt, at))
                dao.createTask(record)
            }
            record
        }
        dispatchSafely(task.id)
        return task.id
    }
    private suspend fun dispatchSafely(id: String) {
        try { withContext(Dispatchers.Main) { dispatch(id) } }
        catch (_: Exception) { pause(id, "Foreground CLI task service unavailable") }
    }
    override suspend fun awaitIdle() { mutex.withLock { execution?.takeUnless { it.isCompleted } }?.join() }
    override suspend fun launchFromService(taskId: String, scope: CoroutineScope) {
        services.ready.await()
        val previous = mutex.withLock {
            if (executingId == taskId && execution?.isCompleted == false) return
            execution?.takeUnless { it.isCompleted }
        }
        previous?.join()
        mutex.withLock {
            val task = dao.task(taskId) ?: return
            if (!isCli(task) || task.activeSlot != 1 || task.status != TaskPhase.Queued.name || execution?.isCompleted == false) return
            executingId = taskId
            execution = scope.launch { run(taskId) }
        }
    }
    override suspend fun retry(taskId: String) {
        services.ready.await()
        awaitIdle()
        val changed = mutex.withLock {
            val task = dao.task(taskId) ?: return
            if (!isCli(task) || task.activeSlot != 1 || task.status != TaskPhase.Paused.name) return
            dao.phase(taskId, TaskPhase.Queued.name, "Checking recorded CLI claims before reconnecting", null, 1, now())
        }
        if (changed == 1) dispatchSafely(taskId)
    }
    override fun observe(taskId: String): Flow<RunnerEvent> = merge(recordedEvents(taskId), textEvents.filter { it.taskId == taskId })
    private fun recordedEvents(taskId: String): Flow<RunnerEvent> =
        dao.observeTask(taskId).filterNotNull().flatMapLatest { task -> dao.observeActions(taskId).transform { actions ->
            emit(RunnerEvent.State(taskId, TaskPhase.valueOf(task.status), task.detail, task.recoveryAction))
            actions.forEach { action ->
                if (action.status == "AWAITING_APPROVAL" && action.decision == null) emit(RunnerEvent.Approval(taskId,
                    action.key(), ApprovalCategory.valueOf(action.category!!), action.tool, action.summary, action.preview))
                action.decision?.let { emit(RunnerEvent.Receipt(taskId, decision(action.key(), it, action.allEdits))) }
                action.outcome?.let { emit(RunnerEvent.ToolResult(taskId, action.id, action.toolCallId, RuntimeCodec.outcome(it), action.buildId)) }
            }
        } }
    override suspend fun answerApproval(decision: ApprovalDecision): Boolean {
        val task = dao.task(decision.key.taskId) ?: return false
        if (!isCli(task)) return false
        val key = decision.key
        val accepted = dao.answer(key.taskId, key.actionId, key.toolCallId, key.buildId, decision.name(),
            (decision as? ApprovalDecision.Approved)?.allEdits == true, now())
        if (accepted) note(task, "${key.actionId}:receipt", "notice", "Approval receipt: ${decision.name().lowercase()} · action ${key.actionId.take(8)}")
        return accepted
    }
    override suspend fun recover() {
        dao.pauseAfterDeath(now(), AgentBackend.Codex.name)
        dao.pauseAfterDeath(now(), AgentBackend.AntigravityCli.name)
        dao.active()?.takeIf(::isCli)?.let { task ->
            val stopping = task.status == TaskPhase.Cancelled.name
            dao.closePending(task.id, if (stopping) "CANCELLED" else "INTERRUPTED", now())
            settle(task.id, stopping)
            if (stopping) dao.cancellationDetail(task.id, "Stop was interrupted; CLI termination is unconfirmed",
                "Retry cancellation to confirm termination before starting another task.", now())
            conversation(task.id, if (stopping) "CANCELLED" else "PAUSED")
        }
    }
    override suspend fun cancel(taskId: String) = withContext(NonCancellable) {
        services.ready.await()
        val job = mutex.withLock {
            val task = dao.task(taskId) ?: return@withContext
            if (!isCli(task) || task.activeSlot != 1) return@withContext
            dao.phase(taskId, TaskPhase.Cancelled.name, "Stop requested; checking CLI termination", null, 1, now())
            dao.closePending(taskId, "CANCELLED", now())
            if (executingId == taskId) execution else null
        }
        job?.cancel(); job?.join()
        settle(taskId, true)
        val task = dao.task(taskId) ?: return@withContext
        task.changeSetId?.let { services.changes.finish(it) }
        val confirmed = try {
            // No start claim means no CLI process was ever dispatched by this task.
            if (dao.action(taskId, "cli-start") == null) true else withTimeout(15_000) { bridge.cancel(taskId) }.stopped()
        } catch (_: Exception) { false }
        if (confirmed) {
            dao.cancellationDetail(taskId, "Stopped; no user decline recorded", null, now())
            dao.releaseCancelled(taskId)
        } else dao.cancellationDetail(taskId, "Stop requested; CLI termination unconfirmed",
            "Retry cancellation to confirm termination before starting another task.", now())
        conversation(taskId, "CANCELLED")
    }
    private suspend fun settle(id: String, stopping: Boolean) {
        val journal = RoomAgentJournal(database, id, ::now)
        dao.actions(id).filter { it.outcome == null }.forEach { action ->
            journal.finish(action.key(), RecordedExecution(if (stopping && action.status != "RUNNING") ToolOutcome.Cancelled else ToolOutcome.Interrupted))
        }
    }
    private suspend fun pause(id: String, reason: String) {
        if (dao.phase(id, TaskPhase.Paused.name, "Paused: $reason", "Retry this provider reconnects to the recorded task; completed or uncertain writes are never resent.", 1, now()) == 1)
            conversation(id, "PAUSED")
        streaming.value = id to ""
    }
    private suspend fun conversation(id: String, state: String) {
        val task = dao.task(id) ?: return
        database.conversations().find(task.conversationId)?.let { database.conversations().save(it.copy(status = state, updatedAt = now())) }
    }
    private suspend fun note(task: RuntimeTaskRecord, id: String, role: String, text: String) {
        if (text.isNotBlank() && database.conversations().message(id) == null)
            database.conversations().saveMessage(MessageRecord(id, task.conversationId, role, text.take(64_000), now()))
    }
    private fun identity(prefix: String, value: String) = prefix + UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
    private suspend fun action(id: String, call: String, tool: String, args: String, preview: ToolPreview,
        category: ApprovalCategory?): RuntimeActionRecord {
        dao.action(id, call)?.let { existing ->
            check(existing.tool == tool && existing.arguments == args) { "CLI action identity changed" }
            return existing
        }
        val journal = RoomAgentJournal(database, id, ::now)
        val key = journal.begin(AgentItem.ToolCall(call, tool, args))
        journal.prepared(key, preview, category)
        return dao.action(id, call)!!
    }
    private suspend fun send(task: RuntimeTaskRecord, command: String, message: JSONObject) {
        val row = action(task.id, command, "cli_send", message.toString(), ToolPreview("CLI protocol delivery"), null)
        // A claim with an unknown outcome is never resent, even if its pipe write may not have happened.
        if (row.status != "READY" || row.outcome != null || dao.claim(row.id, now()) != 1) return
        val sent = bridge.send(task.id, command, message)
        RoomAgentJournal(database, task.id, ::now).finish(row.key(), RecordedExecution(
            if (sent) ToolOutcome.Success("CLI input delivered; execution outcome is observed separately") else ToolOutcome.Interrupted))
    }
    private suspend fun approval(task: RuntimeTaskRecord, event: CliEvent.Approval, answerId: String): CliAnswer {
        val call = identity("cli-approval-", event.requestId)
        val args = JSONObject().put("request", event.requestId).put("item", event.itemId).toString()
        val row = action(task.id, call, if (event.category == ApprovalCategory.Edit) "cli_file_change" else "cli_command", args,
            ToolPreview(event.summary, event.detail), if (event.supported) event.category else null)
        val journal = RoomAgentJournal(database, task.id, ::now)
        if (!event.supported) {
            journal.finish(row.key(), RecordedExecution(ToolOutcome.RuntimeUnavailable("CLI requested permissions outside the reviewed workspace; open Termux to review the action")))
            return CliAnswer.Cancelled
        }
        if (row.decision == null && row.outcome == null) {
            check(dao.phase(task.id, TaskPhase.AwaitingApproval.name, "Waiting for approval: ${event.summary}", null, 1, now()) == 1)
            if (event.category == ApprovalCategory.Edit && dao.task(task.id)?.autoApproveEdits == true)
                dao.answer(task.id, row.id, row.toolCallId, null, "APPROVED", false, now())
        }
        val resolved = if (row.decision == null && row.outcome == null) awaitLiveDecision(task.id, row.id) else row
        dao.phase(task.id, TaskPhase.Running.name, "CLI approval recorded; observing execution", null, 1, now())
        if (resolved.outcome != null && resolved.status == "INTERRUPTED" && dao.action(task.id, answerId) == null)
            return CliAnswer.Interrupted
        return when (resolved.decision) {
            "APPROVED" -> { if (resolved.outcome == null && resolved.status == "APPROVED") dao.claim(resolved.id, now()); CliAnswer.Approved }
            "DECLINED" -> {
                journal.finish(row.key(), RecordedExecution(ToolOutcome.Cancelled, "The user explicitly declined this CLI action.", "DECLINED")); CliAnswer.Declined }
            "CANCELLED" -> { journal.finish(row.key(), RecordedExecution(ToolOutcome.Cancelled)); CliAnswer.Cancelled }
            else -> { journal.finish(row.key(), RecordedExecution(ToolOutcome.Interrupted)); CliAnswer.Interrupted }
        }
    }
    /** The CLI holds its request open; if it or the bridge goes away, the prompt must not stay answerable. */
    private suspend fun awaitLiveDecision(taskId: String, actionId: String): RuntimeActionRecord = coroutineScope {
        val watcher = launch {
            while (true) {
                delay(livenessInterval)
                val alive = try { bridge.status(taskId).state == "RUNNING" } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (!alive) throw CliStoppedException()
            }
        }
        try { dao.observeActions(taskId).map { list -> list.first { it.id == actionId } }.first { it.decision != null || it.outcome != null } }
        finally { watcher.cancel() }
    }
    private suspend fun toolResult(task: RuntimeTaskRecord, event: CliEvent.ToolResult) {
        val approved = dao.actions(task.id).lastOrNull { it.tool in setOf("cli_file_change", "cli_command") &&
            BridgeSecurity.json(it.arguments).requiredText("item", 128) == event.itemId }
        val row = approved ?: action(task.id, identity("cli-item-", event.itemId), "cli_${event.tool}", "{}", ToolPreview("CLI ${event.tool}"), null)
        RoomAgentJournal(database, task.id, ::now).finish(row.key(), RecordedExecution(event.outcome))
    }

    private suspend fun run(id: String) {
        var task = dao.task(id) ?: return
        if (!isCli(task) || dao.phase(id, TaskPhase.Running.name, "Local CLI working", null, 1, now()) != 1) return
        streaming.value = id to ""
        try {
            val backend = AgentBackend.valueOf(task.backend)
            bridge.unavailable(backend)?.let { unavailable ->
                if (dao.action(id, "cli-start") == null) finishTask(task, CliEvent.Finished(unavailable))
                else pause(id, unavailable.reason)
                return
            }
            var data = checkpoint(task)
            if (task.nextStep == "Snapshot") {
                // Before the Start checkpoint nothing was dispatched from a reservation whose creator died mid-copy.
                workspaces.discardIncomplete(id)
                val project = services.projects.find(task.projectId) ?: error("Project not found")
                val snapshot = if (workspaces.exists(id)) workspaces.recorded(id) else workspaces.create(id, task.projectId, services.projects.directory(project))
                data.put("cwd", bridge.prepare(snapshot)); save(id, data, 0, "Start")
                task = dao.task(id)!!
            }
            if (task.nextStep == "Start") {
                val args = JSONObject().put("backend", task.backend).put("session", data.opt("session")).toString()
                val row = action(id, "cli-start", "cli_start", args, ToolPreview("Start official CLI in private workspace"), null)
                if (row.status == "READY" && row.outcome == null && dao.claim(row.id, now()) == 1) {
                    val started = bridge.start(id, backend, data.nullableText("resumeSession"))
                    RoomAgentJournal(database, id, ::now).finish(row.key(), RecordedExecution(
                        if (started) ToolOutcome.Success("CLI process started; turn outcome is observed separately") else ToolOutcome.Interrupted))
                }
                val status = bridge.status(id)
                when {
                    status.state == "RUNNING" || (status.stopped() && status.eventCount > 0) -> Unit // Drain what it wrote.
                    status.stopped() -> {
                        finishTask(task, CliEvent.Finished(ToolOutcome.RuntimeUnavailable("CLI did not start; check installation and permissions in Termux")))
                        return
                    }
                    else -> { pause(id, "CLI start outcome or termination unconfirmed"); return }
                }
                save(id, data, task.completedSteps, "Observe"); task = dao.task(id)!!
            }
            data = checkpoint(task)
            if (data.optBoolean("drained")) { collectChanges(task, data); return }
            val cwd = data.requiredText("cwd", 1024)
            val session = data.nullableText("resumeSession")
            val codex = if (backend == AgentBackend.Codex) CodexProtocol(cwd, task.prompt, session) else null
            val antigravity = if (codex == null) AntigravityProtocol(cwd, session) else null
            val initial = codex?.begin() ?: antigravity!!.user(task.prompt)
            send(task, "cli-initial", initial)
            suspend fun consume(record: JSONObject) {
                val sequence = record.integer("sequence")
                val batch = when (record.requiredText("kind", 32)) {
                    "cli" -> codex?.receive(record.getJSONObject("message")) ?: antigravity!!.receive(record.getJSONObject("message"))
                    "permission_unavailable" -> { antigravity?.stderr("permission unavailable"); data.put("denied", true); CliBatch() }
                    "exit" -> {
                        val code = record.integer("code").also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw BridgeProtocolException() }.toInt()
                        CliBatch(listOf(codex?.exited(code) ?: antigravity!!.exited(code)))
                    }
                    else -> throw BridgeProtocolException() // Native MCP requests need their own approved handler.
                }
                batch.events.forEach { event -> when (event) {
                    is CliEvent.Session -> data.put("session", event.id)
                    is CliEvent.Text -> {
                        streaming.update { id to (it.second + event.delta).takeLast(64_000) }
                        textEvents.emit(RunnerEvent.Text(id, event.delta))
                    }
                    is CliEvent.Approval -> {
                        val answer = approval(task, event, "cli-answer-$sequence")
                        codex?.answer(event.requestId, answer)?.let { send(task, "cli-answer-$sequence", it) }
                    }
                    is CliEvent.ToolResult -> toolResult(task, event)
                    is CliEvent.Finished -> {
                        // A later exit report may refine a recorded success, never upgrade a recorded failure.
                        val previous = data.optJSONObject("terminal")?.let { RuntimeCodec.outcome(it.requiredText("outcome")) }
                        if (previous == null || previous is ToolOutcome.Success)
                            data.put("terminal", JSONObject().put("outcome", RuntimeCodec.outcome(event.outcome)).put("response", event.response))
                    }
                } }
                batch.writes.forEachIndexed { index, write -> send(task, "cli-event-$sequence-$index", write) }
                save(id, data, sequence.toInt(), "Observe")
            }
            val recorded = events.read(id)
            check(recorded.size >= task.completedSteps) { "CLI event checkpoint is missing" }
            recorded.forEach { consume(it) }
            var after = recorded.size.toLong()
            var cleanupDeadline: Long? = null
            // A terminal turn is not the end of the stream: stop the CLI, then drain every event it wrote,
            // so a trailing diagnostic (for example a permission denial) is never lost behind a success.
            while (true) {
                currentCoroutineContext().ensureActive()
                if (data.has("terminal") && cleanupDeadline == null) {
                    cleanupDeadline = System.nanoTime() / 1_000_000 + cleanupTimeout
                    bridge.cancel(id)
                }
                val page = bridge.observe(id, after)
                for (record in page.events) { events.append(id, record); consume(record); after = record.integer("sequence") }
                val state = page.state
                if (state.state == "NOT_FOUND") throw BridgeProtocolException()
                if (after >= state.eventCount && state.stopped()) {
                    if (!data.has("terminal")) {
                        val result = codex?.exited(state.exitCode ?: -1) ?: antigravity!!.exited(state.exitCode ?: -1)
                        data.put("terminal", JSONObject().put("outcome", RuntimeCodec.outcome(result.outcome)).put("response", result.response))
                    }
                    val terminal = data.getJSONObject("terminal")
                    if (data.optBoolean("denied") && RuntimeCodec.outcome(terminal.requiredText("outcome")) is ToolOutcome.Success)
                        terminal.put("outcome", RuntimeCodec.outcome(ToolOutcome.RuntimeUnavailable(
                            "CLI reported a permission denial; open Termux to review the action. No user decline recorded")))
                    data.put("drained", true); save(id, data, after.toInt(), "Capture"); break
                }
                if (state.state !in activeStates) {
                    if (state.cancellationUnconfirmed) { pause(id, "CLI termination unconfirmed"); return }
                    if (cleanupDeadline == null) cleanupDeadline = System.nanoTime() / 1_000_000 + cleanupTimeout
                }
                if (cleanupDeadline != null && System.nanoTime() / 1_000_000 > cleanupDeadline) {
                    pause(id, "CLI turn recorded; process cleanup or event drain unconfirmed"); return
                }
                delay(250)
            }
            collectChanges(dao.task(id)!!, data)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (dao.task(id)?.status != TaskPhase.Cancelled.name) pause(id, "CLI observation interrupted")
            }
            throw cancelled
        } catch (_: CliStoppedException) {
            dao.closePending(id, "INTERRUPTED", now())
            pause(id, "CLI stopped or disconnected while waiting for approval; no decision was sent")
        } catch (_: Exception) { pause(id, "CLI bridge disconnected or sent invalid data") }
        finally { streaming.value = id to "" }
    }
    private suspend fun collectChanges(task: RuntimeTaskRecord, data: JSONObject) {
        val terminal = data.getJSONObject("terminal")
        val result = CliEvent.Finished(RuntimeCodec.outcome(terminal.requiredText("outcome")), terminal.requiredText("response"))
        if (!bridge.cancel(task.id).stopped()) { pause(task.id, "CLI turn recorded; process cleanup unconfirmed"); return }
        resultRoot.mkdirs()
        val archive = File(resultRoot, "${task.id}.zip")
        if (task.nextStep != "Import") {
            bridge.capture(task.id, archive)
            data.put("resultHash", BuildSnapshot.sha256(archive))
            save(task.id, data, task.completedSteps, "Import")
        }
        check(archive.isFile && BuildSnapshot.sha256(archive) == data.requiredText("resultHash", 64)) { "CLI result archive changed" }
        val differences = workspaces.differences(task.id, archive)
        if (differences.isNotEmpty()) {
            val project = services.projects.find(task.projectId) ?: error("Project not found")
            val workspace = services.workspace(project)
            val args = JSONObject().put("archiveHash", data.requiredText("resultHash", 64)).toString()
            val preview = differences.joinToString("\n") { diff ->
                "${diff.kind}: ${diff.path}\n" + TextDiff.unified(diff.path, diff.before, diff.after).take(4000)
            }.take(60_000)
            val row = action(task.id, "cli-import", "import_cli_changes", args,
                ToolPreview("Import ${differences.size} CLI file change(s) into Changes", preview), ApprovalCategory.Edit)
            if (row.outcome == null && row.status == "AWAITING_APPROVAL") {
                workspaces.checkConflicts(workspace, differences)
                check(dao.phase(task.id, TaskPhase.AwaitingApproval.name, "Review returned CLI changes", null, 1, now()) == 1)
                if (dao.task(task.id)?.autoApproveEdits == true) dao.answer(task.id, row.id, row.toolCallId, null, "APPROVED", false, now())
            }
            val resolved = if (row.outcome == null && row.decision == null) dao.observeActions(task.id)
                .map { list -> list.first { it.id == row.id } }.first { it.decision != null || it.outcome != null } else row
            dao.phase(task.id, TaskPhase.Running.name, "Import decision recorded", null, 1, now())
            val journal = RoomAgentJournal(database, task.id, ::now)
            when {
                resolved.outcome != null -> Unit // Completed or uncertain imports are never repeated.
                resolved.decision == "DECLINED" -> journal.finish(row.key(), RecordedExecution(ToolOutcome.Cancelled,
                    "The user explicitly declined importing these CLI changes. Private result archive is preserved.", "DECLINED"))
                resolved.decision == "APPROVED" && dao.claim(row.id, now()) == 1 -> {
                    workspaces.checkConflicts(workspace, differences)
                    val set = services.changes.open(task.projectId, task.conversationId, "CLI changes: ${task.prompt.take(100)}")
                    dao.changeSet(task.id, set.id)
                    try {
                        differences.forEach { services.changes.apply(set.id, workspace, it.path, it.after, FileBaseline(it.before)) }
                        services.changes.finish(set.id)
                        journal.finish(row.key(), RecordedExecution(ToolOutcome.Success("Imported ${differences.size} file(s); review in Changes")))
                    } catch (error: Exception) {
                        withContext(NonCancellable) {
                            services.changes.finish(set.id)
                            journal.finish(row.key(), RecordedExecution(ToolOutcome.Interrupted))
                        }
                        throw error
                    }
                }
                else -> journal.finish(row.key(), RecordedExecution(ToolOutcome.Interrupted))
            }
            dao.action(task.id, "cli-import")?.outcome?.let { json ->
                val imported = RuntimeCodec.outcome(json)
                if (imported is ToolOutcome.Failed || imported is ToolOutcome.RuntimeUnavailable || imported == ToolOutcome.Interrupted) {
                    finishTask(task, result.copy(outcome = ToolOutcome.Failed("CLI turn recorded; import incomplete. Review Changes and the private result archive.")))
                    return
                }
            }
        }
        finishTask(task, result)
    }
    private suspend fun finishTask(task: RuntimeTaskRecord, result: CliEvent.Finished) {
        note(task, "${task.id}:cli-response", "assistant", result.response)
        note(task, "${task.id}:cli-outcome", "notice", result.outcome.text())
        val phase = if (result.outcome is ToolOutcome.Success) TaskPhase.Completed else TaskPhase.Failed
        if (dao.phase(task.id, phase.name, result.outcome.text().take(1000), null, null, now()) == 1) conversation(task.id, phase.name.uppercase())
        streaming.value = task.id to ""
    }
}

private class CliStoppedException : Exception()

/** Production capability remains closed until ARM64 execution, sandbox and process containment pass on phone. */
object DisabledCliBridge : CliBridgeEndpoint {
    override suspend fun unavailable(backend: AgentBackend) = ToolOutcome.RuntimeUnavailable("${backend.name} phone execution and sandbox checks are unverified")
    private fun unavailable(): Nothing = throw RuntimeUnavailableException("Local CLI bridge is not verified on this phone")
    override suspend fun prepare(snapshot: CliWorkspaceStore.Snapshot): String = unavailable()
    override suspend fun start(taskId: String, backend: AgentBackend, sessionId: String?): Boolean = unavailable()
    override suspend fun send(taskId: String, commandId: String, message: JSONObject): Boolean = unavailable()
    override suspend fun observe(taskId: String, after: Long): CliPage = unavailable()
    override suspend fun status(taskId: String): CliWorkerState = unavailable()
    override suspend fun cancel(taskId: String): CliWorkerState = unavailable()
    override suspend fun capture(taskId: String, destination: File): File = unavailable()
}
