package dev.srimi.antigravitymobile.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** App/service plumbing, separate from the frozen runner contract consumed by either lane. */
interface TaskRunnerLifecycle : AgentTaskRunner {
    val view: StateFlow<RuntimeView>
    suspend fun recover()
    suspend fun retry(taskId: String)
    suspend fun launchFromService(taskId: String, scope: CoroutineScope)
    suspend fun awaitIdle()
}
