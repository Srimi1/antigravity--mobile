package dev.srimi.antigravitymobile

import dev.srimi.antigravitymobile.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Restart/retry through the real loop; fake model and journal represent external boundaries only. */
class RuntimeReplayTest {
    private class DurableJournal : AgentJournal {
        private val delegate = TransientAgentJournal("task")
        override val taskId = delegate.taskId
        var items = emptyList<AgentItem>()
        var steps = 0
        var next = LoopNext.Model
        override suspend fun restore(call: AgentItem.ToolCall) = delegate.restore(call)
        override suspend fun begin(call: AgentItem.ToolCall) = delegate.begin(call)
        override suspend fun prepared(key: ApprovalKey, preview: ToolPreview, category: ApprovalCategory?) = delegate.prepared(key, preview, category)
        override suspend fun claim(key: ApprovalKey) = delegate.claim(key)
        override suspend fun finish(key: ApprovalKey, result: RecordedExecution) = delegate.finish(key, result)
        override suspend fun checkpoint(items: List<AgentItem>, steps: Int, next: LoopNext) {
            this.items = RuntimeCodec.items(RuntimeCodec.transcript(items)); this.steps = steps; this.next = next
        }
    }
    private class Model(private val response: suspend kotlinx.coroutines.flow.FlowCollector<ProviderEvent>.(AgentRequest, Int) -> Unit) : AgentModel {
        override val providerId = "fake"
        val requests = mutableListOf<AgentRequest>()
        override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow { requests += request; response(request, requests.size) }
        override fun cancel() {}
    }
    private class Files : ToolHost {
        var contents = "old"
        val executed = mutableListOf<String>()
        override val specs = listOf(ToolSpec("write_file", "write", "{}"), ToolSpec("install_apk", "install", "{}"))
        override fun requiresApproval(name: String) = true
        override fun approvalCategory(name: String) = if (name == "install_apk") ApprovalCategory.Install else ApprovalCategory.Edit
        override suspend fun describe(call: AgentItem.ToolCall) = ToolPreview(call.name)
        override suspend fun execute(call: AgentItem.ToolCall): ToolOutcome {
            executed += call.name
            if (call.name == "write_file") contents = "new"
            return ToolOutcome.Success("Recorded ${call.name}")
        }
    }
    private val approve = ApprovalGate { key, _, _ -> ApprovalDecision.Approved(key) }
    private val listener = object : AgentListener {}

    @Test fun retryAfterNetworkFailureKeepsWritesAndInstallerReceiptWithoutReexecuting() = runBlocking {
        val journal = DurableJournal(); val files = Files()
        val first = Model { _, turn ->
            if (turn == 1) {
                emit(ProviderEvent.Item(AgentItem.ToolCall("write", "write_file", "{}")))
                emit(ProviderEvent.Item(AgentItem.ToolCall("install", "install_apk", "{}")))
                emit(ProviderEvent.Completed)
            } else throw IOException("disconnected")
        }
        assertThrows(ModelRequestFailure::class.java) {
            runBlocking { AgentOrchestrator(first, files, approve, listener, journal = journal).run("", emptyList(), "change and install") }
        }
        assertEquals("new", files.contents)
        val interruptedRequest = first.requests.last()
        val restarted = Model { _, _ -> emit(ProviderEvent.Item(AgentItem.Assistant("Continued from recorded results"))); emit(ProviderEvent.Completed) }
        AgentOrchestrator(restarted, files, approve, listener, journal = journal).resume("", journal.items, journal.steps, journal.next)
        assertEquals(interruptedRequest.input, restarted.requests.single().input)
        assertEquals(listOf("write_file", "install_apk"), files.executed)
        assertEquals(LoopNext.Done, journal.next)
    }
    @Test fun partialStreamNeverCommitsAssistantOrRunsItsToolCalls() = runBlocking {
        val journal = DurableJournal(); val files = Files(); val published = mutableListOf<String>()
        val broken = Model { _, turn ->
            emit(ProviderEvent.Item(AgentItem.Assistant("Unfinished promise")))
            emit(ProviderEvent.Item(AgentItem.ToolCall("old", "write_file", "{}")))
            throw IOException("stream cut off")
        }
        assertThrows(ModelRequestFailure::class.java) { runBlocking {
            AgentOrchestrator(broken, files, approve, object : AgentListener {
                override suspend fun onAssistantMessage(text: String) { published += text }
            }, journal = journal).run("", emptyList(), "change")
        } }
        assertTrue(files.executed.isEmpty()); assertTrue(published.isEmpty())
        assertEquals(listOf(AgentItem.User("change")), journal.items)
        assertEquals(0, journal.steps)
    }
    @Test fun completedFinalTurnSurvivesDeathWithoutAnotherProviderRequest() = runBlocking {
        val journal = DurableJournal(); val files = Files()
        val completed = Model { _, _ -> emit(ProviderEvent.Item(AgentItem.Assistant("done"))); emit(ProviderEvent.Completed) }
        AgentOrchestrator(completed, files, approve, listener, journal = journal).run("", emptyList(), "x")
        val restarted = Model { _, _ -> fail("Completed model turn must not be repeated") }
        val items = AgentOrchestrator(restarted, files, approve, listener, journal = journal)
            .resume("", journal.items, journal.steps, journal.next)
        assertEquals(AgentItem.Assistant("done"), items.last())
        assertTrue(restarted.requests.isEmpty())
    }
    @Test fun duplicateCallIdentityCannotExecuteTwice() = runBlocking {
        val journal = DurableJournal(); val files = Files()
        val model = Model { _, turn ->
            emit(ProviderEvent.Item(AgentItem.ToolCall("same", "write_file", "{}")))
            if (turn == 1) emit(ProviderEvent.Completed) else emit(ProviderEvent.Completed)
        }
        assertThrows(ModelRequestFailure::class.java) { runBlocking {
            AgentOrchestrator(model, files, approve, listener, journal = journal).run("", emptyList(), "x")
        } }
        assertEquals(listOf("write_file"), files.executed)
    }
    @Test fun staleApprovalKeyNeverExecutesOrReportsDecline() = runBlocking {
        val files = Files(); val results = mutableListOf<RecordedExecution>()
        val model = Model { _, turn ->
            if (turn == 1) emit(ProviderEvent.Item(AgentItem.ToolCall("one", "write_file", "{}")))
            else emit(ProviderEvent.Item(AgentItem.Assistant("done")))
            emit(ProviderEvent.Completed)
        }
        AgentOrchestrator(model, files, { key, _, _ -> ApprovalDecision.Approved(key.copy(actionId = "stale")) }, object : AgentListener {
            override suspend fun onToolFinished(call: AgentItem.ToolCall, preview: ToolPreview, result: RecordedExecution) { results += result }
        }).run("", emptyList(), "x")
        assertTrue(files.executed.isEmpty())
        assertEquals("FAILED", results.single().status)
        assertFalse(results.single().output.contains("user declined", true))
    }
}
