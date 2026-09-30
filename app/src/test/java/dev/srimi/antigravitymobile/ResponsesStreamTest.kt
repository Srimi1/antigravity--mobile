package dev.srimi.antigravitymobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesStreamTest {
    private fun feed(parser: ResponsesStreamParser, vararg events: String) =
        events.flatMap { parser.line("data: $it") + parser.line("") }

    @Test fun parsesTextFunctionCallsReasoningAndCompletion() {
        val parser = ResponsesStreamParser()
        val events = feed(parser,
            """{"type":"response.output_text.delta","delta":"Hel"}""",
            """{"type":"response.output_text.delta","delta":"lo"}""",
            """{"type":"response.output_item.done","item":{"id":"rs_1","type":"reasoning","encrypted_content":"abc","summary":[]}}""",
            """{"type":"response.output_item.done","item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}}""",
            """{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","call_id":"call_9","name":"read_file","arguments":"{\"path\":\"A.kt\"}"}}""",
            """{"type":"response.completed","response":{"id":"resp_1"}}""")
        assertEquals(ProviderEvent.Text("Hel"), events[0])
        val reasoning = (events[2] as ProviderEvent.Item).item as AgentItem.Opaque
        assertFalse(JSONObject(reasoning.json).has("id"))
        assertEquals("abc", JSONObject(reasoning.json).getString("encrypted_content"))
        assertEquals(AgentItem.Assistant("Hello"), (events[3] as ProviderEvent.Item).item)
        assertEquals(AgentItem.ToolCall("call_9", "read_file", """{"path":"A.kt"}"""), (events[4] as ProviderEvent.Item).item)
        assertEquals(ProviderEvent.Completed, events.last())
        assertTrue(parser.completed)
    }

    @Test fun failureCarriesOnlyASafeCode() {
        val parser = ResponsesStreamParser()
        val error = assertThrows(ProviderFailure::class.java) {
            feed(parser, """{"type":"response.failed","response":{"error":{"code":"usage_limit_reached","message":"secret prompt text"}}}""")
        }
        assertTrue(error.message!!.contains("usage_limit_reached"))
        assertFalse(error.message!!.contains("secret"))
    }

    @Test fun multiLineDataAndDoneMarkerAreHandled() {
        val parser = ResponsesStreamParser()
        assertTrue(parser.line("event: response.output_text.delta").isEmpty())
        assertTrue(parser.line("data: {\"type\":\"response.output_text.delta\",").isEmpty())
        assertEquals(listOf(ProviderEvent.Text("x")), parser.line("data: \"delta\":\"x\"}") + parser.line(""))
        assertTrue((parser.line("data: [DONE]") + parser.line("")).isEmpty())
    }

    @Test fun statelessInputReplaysItemsWithoutServerIds() {
        val items = listOf(AgentItem.User("hi"), AgentItem.Opaque("chatgpt", """{"type":"reasoning","encrypted_content":"e"}"""),
            AgentItem.Opaque("other", "{}"), AgentItem.ToolCall("c1", "list_files", "{}"), AgentItem.ToolResult("c1", "A.kt"),
            AgentItem.Assistant("done"))
        val body = ResponsesWire.body("model-x", AgentRequest("Be careful", items,
            listOf(ToolSpec("list_files", "List", """{"type":"object","properties":{}}"""))))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        val input = body.getJSONArray("input")
        assertEquals(5, input.length())
        assertEquals("reasoning", input.getJSONObject(1).getString("type"))
        assertEquals("function_call", input.getJSONObject(2).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(3).getString("type"))
        assertEquals("assistant", input.getJSONObject(4).getString("role"))
        assertEquals("function", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertFalse(ResponsesWire.body("m", AgentRequest("", items, emptyList()), false).has("include"))
    }
}
