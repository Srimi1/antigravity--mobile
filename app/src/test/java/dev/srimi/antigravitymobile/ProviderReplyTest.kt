package dev.srimi.antigravitymobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderReplyTest {
    @Test fun unlabelledEventStreamIsParsed() {
        val body = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"ready"}

            data: {"type":"response.completed","response":{}}

        """.trimIndent()
        val events = ResponsesWire.unlabelled(200, "application/octet-stream", body)
        assertEquals(listOf(ProviderEvent.Text("ready"), ProviderEvent.Completed), events)
    }
    @Test fun completedNonStreamingResponseIsAccepted() {
        val body = """{"object":"response","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ready"}]}]}"""
        assertEquals(listOf(ProviderEvent.Item(AgentItem.Assistant("ready")), ProviderEvent.Completed),
            ResponsesWire.unlabelled(200, "application/json", body))
    }
    @Test fun jsonErrorExplainsCodeWithoutSecrets() {
        val body = """{"error":{"code":"subscription_sharing_usage_unavailable","message":"Not available for Bearer eyJabc.def token"}}"""
        val message = assertThrows(ProviderFailure::class.java) { ResponsesWire.unlabelled(200, "application/json; charset=utf-8", body) }.message!!
        assertTrue(message, message.contains("subscription_sharing_usage_unavailable"))
        assertTrue(message, message.contains("application/json"))
        assertFalse(message, message.contains("eyJabc"))
    }
    @Test fun webPageIsReportedAsInterception() {
        val message = ResponsesWire.describe(200, "text/html", "<html>Login to Wi-Fi</html>")
        assertTrue(message, message.contains("web page") && !message.contains("Login to Wi-Fi"))
    }
    @Test fun incompleteStreamIsRejected() {
        assertThrows(ProviderFailure::class.java) {
            ResponsesWire.unlabelled(200, "", "data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\n\n")
        }
    }

    @Test fun geminiModelsAreFilteredAndOrdered() {
        val json = JSONObject("""{"models":[
            {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-2.0-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-2.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""")
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-2.5-flash-preview-tts"),
            GeminiWire.ordered(GeminiWire.models(json)))
    }
    @Test fun geminiStreamProducesTextCallsAndReplayableTurn() {
        val parser = GeminiStreamParser()
        val chunks = listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Let me look. "}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"read_file","args":{"path":"a.txt"}},"thoughtSignature":"sig"}]},"finishReason":"STOP"}]}""")
        val events = chunks.flatMap { parser.line("data: $it") + parser.line("") } + parser.finish()
        assertEquals(ProviderEvent.Text("Let me look. "), events.first())
        val items = events.filterIsInstance<ProviderEvent.Item>().map { it.item }
        val opaque = items[0] as AgentItem.Opaque
        assertEquals(GeminiWire.PROVIDER, opaque.provider)
        assertTrue(opaque.json.contains("\"thoughtSignature\":\"sig\""))
        assertEquals(AgentItem.Assistant("Let me look. "), items[1])
        val call = items[2] as AgentItem.ToolCall
        assertEquals("read_file", call.name); assertTrue(call.callId.startsWith("local-"))
        assertEquals(ProviderEvent.Completed, events.last())

        // Replaying: the stored turn is sent verbatim, copies are skipped, results are grouped with their names.
        val contents = GeminiWire.contents(listOf(AgentItem.User("hi")) + items + AgentItem.ToolResult(call.callId, "file text"))
        assertEquals(3, contents.length())
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("sig", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(1).getString("thoughtSignature"))
        val response = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("read_file", response.getString("name")); assertFalse(response.has("id"))
        assertEquals("file text", response.getJSONObject("response").getString("output"))
    }
    @Test fun geminiProviderIdsAreEchoedAndToolsUseJsonSchema() {
        val items = listOf(AgentItem.User("x"), AgentItem.ToolCall("abc", "git_status", "{}"), AgentItem.ToolResult("abc", "clean"))
        val body = GeminiWire.body(AgentRequest("be brief", items, listOf(ToolSpec("git_status", "status", """{"type":"object","properties":{},"additionalProperties":false}"""))))
        val contents = body.getJSONArray("contents")
        assertEquals("abc", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall").getString("id"))
        assertEquals("abc", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getString("id"))
        assertEquals("be brief", body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val declaration = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertFalse(declaration.getJSONObject("parametersJsonSchema").getBoolean("additionalProperties"))
    }
    @Test fun geminiErrorsAndBlocksAreExplainedWithoutKeys() {
        val message = GeminiWire.describe(400, """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"API key not valid. AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ012"}}""")
        assertTrue(message, message.contains("INVALID_ARGUMENT") && message.contains("Check the key"))
        assertFalse(message, message.contains("AIzaSy"))
        val parser = GeminiStreamParser()
        assertThrows(ProviderFailure::class.java) { parser.line("data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"); parser.line("") }
        val unfinished = GeminiStreamParser()
        unfinished.line("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]}}]}"); unfinished.line("")
        assertThrows(ProviderFailure::class.java) { unfinished.finish() }
        JSONArray() // keep import used
    }
}
