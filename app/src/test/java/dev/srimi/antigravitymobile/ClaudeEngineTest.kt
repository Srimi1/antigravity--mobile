package dev.srimi.antigravitymobile

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The official SDK against a local server speaking the Messages API wire format. No real key or network. */
class ClaudeEngineTest {
    private val server = MockWebServer().apply { start() }
    private val replies = object { operator fun plusAssign(reply: Triple<Int, String, String>) {
        server.enqueue(MockResponse().setResponseCode(reply.first).setHeader("Content-Type", reply.second).setBody(reply.third))
    } }
    private val taken = mutableListOf<Pair<Map<String, String>, String>>()
    private val requests: List<Pair<Map<String, String>, String>> get() {
        while (server.requestCount > taken.size) {
            val request = server.takeRequest()
            taken += (request.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") } +
                ("path" to request.requestUrl!!.encodedPath)) to request.body.readUtf8()
        }
        return taken
    }
    private val engine = ClaudeEngine { key ->
        AnthropicOkHttpClient.builder().apiKey(key).baseUrl(server.url("/").toString().removeSuffix("/")).maxRetries(0).timeout(java.time.Duration.ofSeconds(20)).build()
    }
    @After fun stop() = server.shutdown()

    private fun sse(vararg events: String) = events.joinToString("") { e -> "event: ${JSONObject(e).getString("type")}\ndata: $e\n\n" }
    private val start = """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}"""
    private fun stop(reason: String) = """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null},"usage":{"output_tokens":20}}"""
    private val end = """{"type":"message_stop"}"""
    private val tools = listOf(ToolSpec("read_file", "Read a file",
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""))

    @Test fun streamsTextAndToolCallsThenReplaysThinkingAndResultsUnchanged() = runBlocking {
        replies += Triple(200, "text/event-stream", sse(start,
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need the file."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG-123"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Reading "}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"a.txt."}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\": "}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"a.txt\"}"}}""",
            """{"type":"content_block_stop","index":2}""", stop("tool_use"), end))
        val first = engine.turn("sk-ant-test", "claude-opus-5-5", AgentRequest("be careful", listOf(AgentItem.User("read a.txt")), tools)).toList()
        assertEquals(listOf(ProviderEvent.Text("Reading "), ProviderEvent.Text("a.txt.")), first.filterIsInstance<ProviderEvent.Text>())
        val items = first.filterIsInstance<ProviderEvent.Item>().map { it.item }
        assertTrue(items[0] is AgentItem.Opaque)
        assertEquals(AgentItem.Assistant("Reading a.txt."), items[1])
        val call = items[2] as AgentItem.ToolCall
        assertEquals("toolu_1", call.callId); assertEquals("read_file", call.name)
        assertEquals("a.txt", JSONObject(call.arguments).getString("path"))
        assertEquals(ProviderEvent.Completed, first.last())

        val (headers, body) = requests[0]
        assertEquals("/v1/messages", headers["path"])
        assertEquals("sk-ant-test", headers["x-api-key"])
        assertTrue(headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"))
        val json = JSONObject(body)
        assertEquals("claude-opus-5-5", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals("default", json.getString("fallbacks"))
        assertEquals("medium", json.getJSONObject("output_config").getString("effort"))
        assertEquals("be careful", json.get("system").toString().let { if (it.startsWith("[")) JSONObject(org.json.JSONArray(it).getString(0)).getString("text") else it })
        val schema = json.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema")
        assertFalse(schema.getBoolean("additionalProperties")); assertEquals("path", schema.getJSONArray("required").getString(0))

        replies += Triple(200, "text/event-stream", sse(start,
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done."}}""",
            """{"type":"content_block_stop","index":0}""", stop("end_turn"), end))
        val history = listOf(AgentItem.User("read a.txt")) + items + AgentItem.ToolResult("toolu_1", "hello")
        val second = engine.turn("sk-ant-test", "claude-opus-5-5", AgentRequest("be careful", history, tools)).toList()
        assertEquals(ProviderEvent.Completed, second.last())
        val messages = JSONObject(requests[1].second).getJSONArray("messages")
        assertEquals(3, messages.length())
        val assistant = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", assistant.getJSONObject(0).getString("type"))
        assertEquals("SIG-123", assistant.getJSONObject(0).getString("signature"))
        assertEquals("Need the file.", assistant.getJSONObject(0).getString("thinking"))
        assertEquals("tool_use", assistant.getJSONObject(2).getString("type"))
        assertEquals(3, assistant.length())
        val result = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type")); assertEquals("toolu_1", result.getString("tool_use_id"))
    }

    @Test fun refusalAndErrorsAreExplainedWithoutTheKey() = runBlocking {
        replies += Triple(200, "text/event-stream", sse(start, stop("refusal"), end))
        val refused = runCatching { engine.turn("sk-ant-test", "claude-opus-5-5", AgentRequest("", listOf(AgentItem.User("x")), emptyList())).toList() }
        assertTrue(refused.exceptionOrNull()?.message.orEmpty().contains("declined"))
        replies += Triple(401, "application/json", """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        val unauthorized = runCatching { engine.catalog("sk-ant-secret-value") }.exceptionOrNull()
        assertTrue(unauthorized is ProviderFailure)
        val message = unauthorized!!.message.orEmpty()
        assertTrue(message, message.contains("HTTP 401") && message.contains("invalid"))
        assertFalse(message, message.contains("sk-ant-secret"))
        assertFalse(message, message.contains("authentication_error\""))
        assertEquals("Anthropic replied HTTP 401 — x. The API key is invalid; check it in Accounts.",
            ClaudeWire.describe(401, "401: {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"x\"}}"))
    }

    @Test fun modelsListedOpusFirst() {
        replies += Triple(200, "application/json", """{"data":[
            {"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5","created_at":"2025-10-01T00:00:00Z"},
            {"type":"model","id":"claude-sonnet-5-5","display_name":"Claude Sonnet 5.5","created_at":"2026-08-01T00:00:00Z"},
            {"type":"model","id":"claude-opus-4-8","display_name":"Claude Opus 4.8","created_at":"2026-03-01T00:00:00Z"},
            {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-09-01T00:00:00Z"}],
            "has_more":false,"first_id":"claude-haiku-4-5","last_id":"claude-opus-5-5"}""")
        assertEquals(listOf("claude-opus-5-5", "claude-opus-4-8", "claude-sonnet-5-5", "claude-haiku-4-5"), engine.catalog("sk-ant-test").map { it.id })
    }

    @Test fun planFallsBackToCopiesWhenStoredTurnIsGone() {
        val items = listOf(AgentItem.User("u"), AgentItem.Opaque(ClaudeAdapter.PROVIDER, "missing"), AgentItem.Assistant("a"),
            AgentItem.ToolCall("t", "git_status", "{}"), AgentItem.ToolResult("t", "clean"))
        val turns = ClaudeWire.plan(items) { false }
        assertEquals(listOf(false, true, false), turns.map { it.assistant })
        assertEquals(listOf(ClaudeWire.Part.Text("a"), ClaudeWire.Part.Call("t", "git_status", "{}")), turns[1].parts)
        assertEquals("{}", ClaudeWire.normalizeJson("not json"))
    }
}
