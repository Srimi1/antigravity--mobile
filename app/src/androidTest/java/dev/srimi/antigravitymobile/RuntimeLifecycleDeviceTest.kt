package dev.srimi.antigravitymobile

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device lifecycle is real; streamed provider turns come from the test APK's fixture only. */
@RunWith(AndroidJUnit4::class)
class RuntimeLifecycleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val services get() = context.container
    private val prefs get() = context.getSharedPreferences("runtime-device-fixture", Context.MODE_PRIVATE)
    private fun open() = context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun fixtureModel() = object : AgentModel {
        override val providerId = "device-fixture"
        private var turn = 0
        override fun cancel() {}
        override fun streamAgentTurn(request: AgentRequest) = flow {
            turn++
            val call = if (turn == 1) AgentItem.ToolCall("write-call", "write_file", """{"path":"survives.txt","content":"kept after process death"}""")
                else AgentItem.ToolCall("delete-call", "delete_file", """{"path":"survives.txt"}""")
            emit(ProviderEvent.Item(call)); emit(ProviderEvent.Completed)
        }
    }
    private suspend fun seed(): Pair<String, ProjectRecord> {
        services.ready.await(); check(services.database.runtime().active() == null)
        open(); delay(1000)
        RuntimeFixture.model = fixtureModel()
        val project = services.projects.create("Runtime lifecycle fixture") { }
        val id = services.tasks.start(TaskStart(project.id, null, "lifecycle fixture", RuntimeFixture.PROVIDER))
        val write = withTimeout(15_000) { services.database.runtime().observeActions(id)
            .mapNotNull { list -> list.firstOrNull { it.toolCallId == "write-call" && it.status == "AWAITING_APPROVAL" } }.first() }
        withTimeout(15_000) { services.database.runtime().observeTask(id).filterNotNull().first { it.status == TaskPhase.AwaitingApproval.name } }
        assertTrue(services.tasks.answerApproval(ApprovalDecision.Approved(write.key())))
        withTimeout(15_000) { services.database.runtime().observeActions(id)
            .first { list -> list.any { it.toolCallId == "delete-call" && it.status == "AWAITING_APPROVAL" } } }
        return id to project
    }
    @Suppress("DEPRECATION")
    private fun foreground() = context.getSystemService(ActivityManager::class.java).getRunningServices(30)
        .any { it.service.className == AgentTaskService::class.java.name && it.foreground }
    private suspend fun clean(id: String, project: ProjectRecord) {
        services.tasks.cancel(id)
        val conversation = services.database.runtime().task(id)!!.conversationId
        services.database.runtime().deleteActionsForConversation(conversation)
        services.database.runtime().deleteTasksForConversation(conversation)
        services.database.conversations().deleteMessages(conversation)
        services.database.conversations().deleteActions(conversation)
        services.database.conversations().delete(conversation)
        services.projects.delete(project)
        RuntimeFixture.model = null
    }

    @Test fun backgroundAndRotationKeepForegroundTaskAndPendingApproval() = runBlocking {
        val (id, project) = seed()
        try {
            assertTrue(foreground())
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            delay(1500)
            assertTrue(foreground())
            assertEquals(TaskPhase.AwaitingApproval.name, services.database.runtime().task(id)!!.status)
            open(); delay(500)
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            delay(1500)
            assertTrue(foreground())
            val pending = services.database.runtime().action(id, "delete-call")!!
            assertNull(pending.decision)
            assertEquals("kept after process death", services.workspace(project).read("survives.txt"))
            services.tasks.cancel(id)
            assertEquals("CANCELLED", services.database.runtime().action(id, "delete-call")!!.decision)
        } finally { instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE); clean(id, project) }
    }

    /** Run alone, force-stop the target via ADB, then run assertProcessDeathRecoveryAndSafeRetry. */
    @Test fun seedForegroundTaskForProcessDeath(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runtimeProcessDeath") == "seed")
        val (id, project) = seed()
        assertTrue(foreground())
        prefs.edit().putString("task", id).putString("project", project.id)
            .putLong("modified", services.workspace(project).rootDirectory.resolve("survives.txt").lastModified()).commit()
    }

    @Test fun assertProcessDeathRecoveryAndSafeRetry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runtimeProcessDeath") == "recover")
        services.ready.await()
        val id = checkNotNull(prefs.getString("task", null)) { "Run seedForegroundTaskForProcessDeath and force-stop first" }
        val project = checkNotNull(services.projects.find(checkNotNull(prefs.getString("project", null))))
        try {
            val task = services.database.runtime().task(id)!!
            assertEquals(TaskPhase.Paused.name, task.status)
            assertEquals("COMPLETED", services.database.runtime().action(id, "write-call")!!.status)
            assertEquals("INTERRUPTED", services.database.runtime().action(id, "delete-call")!!.decision)
            assertFalse(services.database.conversations().messages(task.conversationId).any { it.content.contains("user declined") })
            var requests = 0
            RuntimeFixture.model = object : AgentModel {
                override val providerId = "device-fixture"
                override fun cancel() {}
                override fun streamAgentTurn(request: AgentRequest) = flow {
                    requests++
                    val results = request.input.filterIsInstance<AgentItem.ToolResult>()
                    assertEquals(setOf("write-call", "delete-call"), results.map { it.callId }.toSet())
                    assertTrue(results.first { it.callId == "delete-call" }.output.contains("interrupted", ignoreCase = true))
                    emit(ProviderEvent.Item(AgentItem.Assistant("Resumed from recorded results"))); emit(ProviderEvent.Completed)
                }
            }
            open(); delay(500); services.tasks.retry(id)
            withTimeout(15_000) { services.database.runtime().observeTask(id).filterNotNull().first { it.status == TaskPhase.Completed.name } }
            assertEquals(1, requests)
            assertEquals("kept after process death", services.workspace(project).read("survives.txt"))
            assertEquals(prefs.getLong("modified", 0), services.workspace(project).rootDirectory.resolve("survives.txt").lastModified())
            assertEquals(2, services.database.runtime().actions(id).size)
        } finally { clean(id, project); prefs.edit().clear().commit() }
    }
}
