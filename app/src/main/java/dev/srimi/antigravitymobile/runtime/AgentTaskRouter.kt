package dev.srimi.antigravitymobile.runtime

import dev.srimi.antigravitymobile.RuntimeDao
import dev.srimi.antigravitymobile.bridge.CliAgentTaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared Room slot plus cleanup barrier enforces one task across native and CLI backends. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentTaskRouter(private val dao: RuntimeDao, private val native: NativeAgentTaskRunner,
    private val cli: CliAgentTaskRunner, scope: CoroutineScope) : TaskRunnerLifecycle {
    private val commands = Mutex()
    private fun runner(backend: String): TaskRunnerLifecycle = when (AgentBackend.valueOf(backend)) {
        AgentBackend.Native -> native
        AgentBackend.Codex, AgentBackend.AntigravityCli -> cli
    }
    override val view = dao.observeActive().flatMapLatest { task ->
        if (task == null) flowOf(RuntimeView()) else runner(task.backend).view
    }.stateIn(scope, SharingStarted.Eagerly, RuntimeView())
    override suspend fun start(request: TaskStart): String = commands.withLock {
        check(dao.active() == null) { "Finish, retry or stop the current task first" }
        awaitIdle()
        runner(request.backend.name).start(request)
    }
    override fun observe(taskId: String): Flow<RunnerEvent> = dao.observeTask(taskId).filterNotNull()
        .take(1).flatMapLatest { runner(it.backend).observe(taskId) }
    override suspend fun answerApproval(decision: ApprovalDecision): Boolean =
        dao.task(decision.key.taskId)?.let { runner(it.backend).answerApproval(decision) } ?: false
    override suspend fun cancel(taskId: String) = commands.withLock {
        dao.task(taskId)?.let { runner(it.backend).cancel(taskId) }; Unit
    }
    override suspend fun retry(taskId: String) = commands.withLock {
        dao.task(taskId)?.let { runner(it.backend).retry(taskId) }; Unit
    }
    override suspend fun launchFromService(taskId: String, scope: CoroutineScope) = commands.withLock {
        dao.task(taskId)?.let { runner(it.backend).launchFromService(taskId, scope) }; Unit
    }
    override suspend fun recover() { native.recover(); cli.recover() }
    override suspend fun awaitIdle() { native.awaitIdle(); cli.awaitIdle() }
    suspend fun unavailable(backend: AgentBackend): ToolOutcome.RuntimeUnavailable? =
        if (backend == AgentBackend.Native) null else cli.unavailable(backend)
}
