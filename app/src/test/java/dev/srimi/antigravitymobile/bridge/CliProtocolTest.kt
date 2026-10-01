package dev.srimi.antigravitymobile.bridge

import dev.srimi.antigravitymobile.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CliProtocolTest {
    private val workspace = "/home/agm/workspaces/task-1"
    private fun json(text: String) = JSONObject(text)
    private fun ready(sandbox: String = "workspaceWrite"): Pair<CodexProtocol, CliBatch> {
        val protocol = CodexProtocol(workspace, "Build game")
        val initial = protocol.begin()
        assertEquals("initialize", initial.getString("method"))
        val initialized = protocol.receive(json("""{"id":"agm-init","result":{"userAgent":"codex"}}"""))
        assertEquals(listOf("initialized", "thread/start"), initialized.writes.map { it.getString("method") })
        val opened = protocol.receive(json("""{"id":"agm-thread","result":{"thread":{"id":"thread-1"},"cwd":"$workspace","sandbox":{"type":"$sandbox","networkAccess":false},"approvalPolicy":"on-request","approvalsReviewer":"user"}}"""))
        return protocol to opened
    }
    private fun running(): CodexProtocol = ready().first.also {
        it.receive(json("""{"id":"agm-turn","result":{"turn":{"id":"turn-1","status":"inProgress"}}}"""))
    }

    @Test fun codexStartsOnlyAfterHandshakeAndConfirmedWorkspaceSandbox() {
        val (_, opened) = ready()
        assertEquals(CliEvent.Session("thread-1"), opened.events.single())
        val turn = opened.writes.single()
        assertEquals("turn/start", turn.getString("method"))
        assertEquals("workspaceWrite", turn.getJSONObject("params").getJSONObject("sandboxPolicy").getString("type"))
        val (_, unsafe) = ready("dangerFullAccess")
        assertTrue(unsafe.writes.isEmpty())
        assertTrue((unsafe.events.single() as CliEvent.Finished).outcome is ToolOutcome.RuntimeUnavailable)
    }

    @Test fun codexApprovalsRemainIdentifiableAndCancelNeverMeansDecline() {
        val protocol = running()
        val event = protocol.receive(json("""{"id":8,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1","command":"echo ready","cwd":"$workspace"}}"""))
        val approval = event.events.single() as CliEvent.Approval
        assertEquals(ApprovalCategory.Command, approval.category)
        val cancelled = protocol.answer(approval.requestId, CliAnswer.Cancelled)!!
        assertEquals("cancel", cancelled.getJSONObject("result").getString("decision"))
        assertNull(protocol.answer(approval.requestId, CliAnswer.Approved))
        val unknown = protocol.receive(json("""{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"command-1","type":"commandExecution","status":"declined","aggregatedOutput":"","exitCode":null}}}"""))
        val outcome = (unknown.events.single() as CliEvent.ToolResult).outcome
        assertTrue(outcome is ToolOutcome.RuntimeUnavailable)
        assertFalse((outcome as ToolOutcome.RuntimeUnavailable).reason.contains("user", ignoreCase = true))
    }

    @Test fun codexRejectsOtherThreadAndReportsActualToolFailure() {
        val protocol = running()
        assertThrows(BridgeProtocolException::class.java) {
            protocol.receive(json("""{"method":"item/agentMessage/delta","params":{"threadId":"other","turnId":"turn-1","itemId":"text-1","delta":"fake"}}"""))
        }
        val failed = protocol.receive(json("""{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"command-2","type":"commandExecution","status":"failed","aggregatedOutput":"compiler failed","exitCode":7}}}"""))
        assertTrue((failed.events.single() as CliEvent.ToolResult).outcome is ToolOutcome.Failed)
        assertEquals("turn/interrupt", protocol.interrupt()!!.getString("method"))
        val stopped = protocol.receive(json("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"interrupted","items":[]}}}"""))
        assertEquals(ToolOutcome.Cancelled, (stopped.events.single() as CliEvent.Finished).outcome)
    }

    @Test fun antigravitySoftDenialStaysUnavailableEvenWhenResultSucceeds() {
        val protocol = AntigravityProtocol(workspace)
        protocol.user("Build game")
        protocol.receive(json("""{"event":"init","conversation_id":"cli-1","init":{"cwd":"$workspace","permission_mode":"request-review"}}"""))
        val denied = protocol.receive(json("""{"event":"step_update","step_update":{"conversation_id":"cli-1","step_index":4,"state":"DONE","step_type":"tool","tool_info":{"name":"run_command","error":{"type":"PERMISSION_DENIED","message":"approval unavailable"}}}}"""))
        assertTrue((denied.events.single() as CliEvent.ToolResult).outcome is ToolOutcome.RuntimeUnavailable)
        val result = protocol.receive(json("""{"event":"result","result":{"conversation_id":"cli-1","status":"SUCCESS","response":"Could not build","num_turns":1}}"""))
        assertTrue((result.events.single() as CliEvent.Finished).outcome is ToolOutcome.RuntimeUnavailable)
        assertFalse(result.toString().contains("user declined", ignoreCase = true))
    }

    @Test fun antigravityRequiresExactSessionAndResultNotExitCode() {
        val protocol = AntigravityProtocol(workspace, "cli-1")
        protocol.user("Continue")
        assertThrows(BridgeProtocolException::class.java) {
            protocol.receive(json("""{"event":"init","conversation_id":"other","init":{"cwd":"$workspace","permission_mode":"request-review"}}"""))
        }
        protocol.receive(json("""{"event":"init","conversation_id":"cli-1","init":{"cwd":"$workspace","permission_mode":"request-review"}}"""))
        assertTrue(protocol.exited(0).outcome is ToolOutcome.Interrupted)
        val unsafe = AntigravityProtocol(workspace)
        unsafe.user("Continue")
        val blocked = unsafe.receive(json("""{"event":"init","conversation_id":"cli-2","init":{"cwd":"$workspace","permission_mode":"always-proceed"}}"""))
        assertTrue((blocked.events.single() as CliEvent.Finished).outcome is ToolOutcome.RuntimeUnavailable)
    }

    @Test fun zeroProcessExitCannotOverwriteRecordedFailedTurn() {
        val codex = running()
        val failed = codex.receive(json("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","items":[]}}}"""))
        assertTrue((failed.events.single() as CliEvent.Finished).outcome is ToolOutcome.Failed)
        assertTrue(codex.exited(0).outcome is ToolOutcome.Failed)

        val agy = AntigravityProtocol(workspace)
        agy.user("Build")
        agy.receive(json("""{"event":"init","conversation_id":"cli-1","init":{"cwd":"$workspace","permission_mode":"request-review"}}"""))
        agy.receive(json("""{"event":"result","result":{"conversation_id":"cli-1","status":"ERROR","response":"","num_turns":0}}"""))
        assertTrue(agy.exited(0).outcome is ToolOutcome.Failed)
    }

    @Test fun missingTurnAndEscalationCannotAuthorizeCommand() {
        val protocol = running()
        assertThrows(BridgeProtocolException::class.java) {
            protocol.receive(json("""{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","itemId":"text-1","delta":"fake"}}"""))
        }
        val request = protocol.receive(json("""{"id":9,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-3","command":"shell","cwd":"$workspace","proposedExecpolicyAmendment":["shell"]}}"""))
        val approval = request.events.single() as CliEvent.Approval
        assertFalse(approval.supported)
        assertEquals("cancel", protocol.answer(approval.requestId, CliAnswer.Approved)!!.getJSONObject("result").getString("decision"))
    }

    @Test fun documentedThreadNotificationAfterResponseIsAcceptedAndBound() {
        val protocol = ready().first
        assertTrue(protocol.receive(json("""{"method":"thread/started","params":{"thread":{"id":"thread-1"}}}""")).events.isEmpty())
        assertThrows(BridgeProtocolException::class.java) {
            protocol.receive(json("""{"method":"thread/started","params":{"thread":{"id":"other"}}}"""))
        }
    }

    @Test fun approvalCannotAcceptAfterStopCompletionOrProcessExit() {
        listOf("stop", "complete", "exit").forEach { end ->
            val protocol = running()
            val approval = protocol.receive(json("""{"id":8,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1","command":"echo ready","cwd":"$workspace"}}""")).events.single() as CliEvent.Approval
            when (end) {
                "stop" -> protocol.interrupt()
                "complete" -> protocol.receive(json("""{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}}"""))
                "exit" -> protocol.exited(0)
            }
            assertNull("stale approval after $end", protocol.answer(approval.requestId, CliAnswer.Approved))
        }
    }

    @Test fun malformedOutcomeFieldsCannotBecomeSuccess() {
        listOf("0.5", "\"0\"").forEach { exitCode ->
            assertThrows(BridgeProtocolException::class.java) {
                running().receive(json("""{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"id":"command-2","type":"commandExecution","status":"completed","exitCode":$exitCode}}}"""))
            }
        }
        val agy = AntigravityProtocol(workspace)
        agy.user("Build")
        agy.receive(json("""{"event":"init","conversation_id":"cli-1","init":{"cwd":"$workspace","permission_mode":"request-review"}}"""))
        assertThrows(BridgeProtocolException::class.java) {
            agy.receive(json("""{"event":"step_update","step_update":{"conversation_id":"cli-1","step_index":4,"state":"DONE","step_type":"tool","tool_info":{"name":"run_command","error":"permission denied"}}}"""))
        }
        assertThrows(BridgeProtocolException::class.java) {
            agy.receive(json("""{"event":"result","result":{"conversation_id":"cli-1","status":"SUCCESS","response":"","num_turns":0.5}}"""))
        }
    }

    @Test fun otherCodexServerRequestsGetSafeRepliesAndRuntimeLimitsNotDeclines() {
        val protocol = running()
        val permissions = protocol.receive(json("""{"id":20,"method":"item/permissions/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"cmd-9","cwd":"$workspace","permissions":{"network":{"enabled":true}},"startedAtMs":1}}"""))
        permissions.writes.single().let { assertEquals(20, it.getInt("id")); assertEquals("{\"permissions\":{}}", it.getJSONObject("result").toString()) }
        assertTrue((permissions.events.single() as CliEvent.ToolResult).outcome is ToolOutcome.RuntimeUnavailable)
        val own = protocol.receive(json("""{"id":21,"method":"mcpServer/elicitation/request","params":{"serverName":"agm_native","threadId":"thread-1","turnId":"turn-1","mode":"form","message":"Allow build_project?","requestedSchema":{"type":"object","properties":{}}}}"""))
        assertEquals("accept", own.writes.single().getJSONObject("result").getString("action"))
        assertTrue("native prompt still follows in the app", own.events.isEmpty())
        val foreign = protocol.receive(json("""{"id":22,"method":"mcpServer/elicitation/request","params":{"serverName":"other","threadId":"thread-1","turnId":"turn-1","mode":"url","elicitationId":"e","message":"Sign in","url":"https://example.com"}}"""))
        assertEquals("decline", foreign.writes.single().getJSONObject("result").getString("action"))
        val question = protocol.receive(json("""{"id":23,"method":"item/tool/requestUserInput","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"ask-1","questions":[]}}"""))
        assertEquals("""{"answers":{}}""", question.writes.single().getJSONObject("result").toString())
        val unknown = protocol.receive(json("""{"id":24,"method":"attestation/generate","params":{}}"""))
        assertEquals(-32601, unknown.writes.single().getJSONObject("error").getInt("code"))
        assertTrue(listOf(permissions, foreign, question, unknown).all { batch -> batch.events.single().let { (it as CliEvent.ToolResult).outcome is ToolOutcome.RuntimeUnavailable } })
        assertThrows(BridgeProtocolException::class.java) { protocol.receive(json("""{"id":24,"method":"attestation/generate","params":{}}""")) }
    }
}
