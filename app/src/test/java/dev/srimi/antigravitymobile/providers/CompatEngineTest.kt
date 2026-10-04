package dev.srimi.antigravitymobile.providers

import dev.srimi.antigravitymobile.AgentItem
import dev.srimi.antigravitymobile.AgentRequest
import dev.srimi.antigravitymobile.ProviderEvent
import dev.srimi.antigravitymobile.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CompatEngineTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryProviderUsageStore()
    private val engine = CompatEngine(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()) { store }
    private val tool = ToolSpec("read_file", "Read a file", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
    private val request = AgentRequest("Be brief.", listOf(AgentItem.User("hi")), listOf(tool))

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun base() = server.url("/v1").toString().trimEnd('/')
    private fun entry(id: String = "groq", rule: FreeRule = FreeRule.AllModels, cls: AllowanceClass = AllowanceClass.Free, expires: Long? = null) =
        ProviderEntry(ProviderDescriptor(id, id, base(), AuthType.ApiKey, cls, null, null, null, expires, BillingRequirement.Unknown, "", "", null),
            rule, CompatQuirks(), "")
    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    @Test fun streamsTextAndToolCallsAndRecordsUsage() = runBlocking {
        server.enqueue(sse(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":"Let me "}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"look."}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.txt\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7}}""",
            "[DONE]"))
        val events = engine.turn(entry(), base(), "test-key-123", "m", request, freeOnly = true, planConfirmed = false).toList()
        assertEquals(listOf("Let me ", "look."), events.filterIsInstance<ProviderEvent.Text>().map { it.delta })
        val items = events.filterIsInstance<ProviderEvent.Item>().map { it.item }
        assertEquals(AgentItem.Assistant("Let me look."), items[0])
        assertEquals(AgentItem.ToolCall("call_1", "read_file", """{"path":"a.txt"}"""), items[1])
        assertEquals(ProviderEvent.Completed, events.last())
        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer test-key-123", sent.getHeader("Authorization"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("read_file", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals(UsageTotals(1, 12, 7, 0), store.totals("groq", null, 0))
    }

    @Test fun rateLimitAndQuotaAreTypedWithoutLeakingTheKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "9")
            .setBody("""{"error":{"code":"rate_limit_exceeded","message":"Slow down, key test-key-123"}}"""))
        val limited = runCatching { engine.turn(entry(), base(), "test-key-123", "m", request, true, false).toList() }.exceptionOrNull()
        assertTrue(limited is ProviderFailure.RateLimited)
        assertEquals(9L, (limited as ProviderFailure.RateLimited).retryAfterSeconds)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"1113","message":"Insufficient balance or no resource package. Please recharge."}}"""))
        val quota = runCatching { engine.turn(entry(), base(), "k", "m", request, true, false).toList() }.exceptionOrNull()
        assertTrue(quota is ProviderFailure.QuotaExhausted)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API Key"}}"""))
        assertTrue(runCatching { engine.turn(entry(), base(), "k", "m", request, true, false).toList() }.exceptionOrNull() is ProviderFailure.AuthInvalid)
    }

    @Test fun pricingChangeStopsBeforeSending() = runBlocking {
        val openrouter = entry("openrouter", FreeRule.ZeroPriced(":free"))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"x/m:free","pricing":{"prompt":"0.000001","completion":"0"}}]}"""))
        val failure = runCatching { engine.turn(openrouter, base(), "k", "x/m:free", request, true, false).toList() }.exceptionOrNull()
        assertTrue(failure is ProviderFailure.PricingChanged)
        assertEquals(1, server.requestCount) // only the catalog; no chat request was sent
        // Free mode off: the user explicitly allowed paid use, so it is sent.
        server.enqueue(sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        assertEquals(ProviderEvent.Completed, engine.turn(openrouter, base(), "k", "x/m:free", request, false, false).toList().last())
    }

    @Test fun trialExpiryAndPaidRoutesAreBlockedInFreeMode() = runBlocking {
        val expired = entry(cls = AllowanceClass.Trial, expires = 1L)
        assertTrue(runCatching { engine.turn(expired, base(), "k", "m", request, true, false).toList() }.exceptionOrNull() is ProviderFailure.TrialExpired)
        val paid = entry("custom", FreeRule.Listed(emptySet()), AllowanceClass.Paid)
        assertTrue(runCatching { engine.turn(paid, base(), "k", "m", request, true, false).toList() }.exceptionOrNull() is ProviderFailure.Rejected)
        val dependent = entry("cloudflare", cls = AllowanceClass.AccountDependent)
        assertTrue(runCatching { engine.turn(dependent, base(), "k", "m", request, true, false).toList() }.exceptionOrNull() is ProviderFailure.Rejected)
        assertEquals(0, server.requestCount)
    }

    @Test fun truncatedStreamIsInterrupted() = runBlocking {
        server.enqueue(sse("""{"choices":[{"index":0,"delta":{"content":"par"}}]}"""))
        val failure = runCatching { engine.turn(entry(), base(), "k", "m", request, true, false).toList() }.exceptionOrNull()
        assertTrue(failure is ProviderFailure.StreamInterrupted)
    }

    @Test fun midStreamErrorEventIsTyped() = runBlocking {
        server.enqueue(sse("""{"error":{"code":"insufficient_quota","message":"quota","status":429}}"""))
        assertTrue(runCatching { engine.turn(entry(), base(), "k", "m", request, true, false).toList() }.exceptionOrNull() is ProviderFailure.QuotaExhausted)
    }

    @Test fun catalogParsesPricesAndToolSupport() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b","pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
            {"id":"a","pricing":{"prompt":"0.1","completion":"0.2"},"supported_parameters":["temperature"]},{"id":"c"}]}"""))
        val models = engine.catalog(entry(), base(), null)
        assertEquals(listOf("a", "b", "c"), models.map { it.id })
        assertTrue(models[1].zeroPriced); assertEquals(true, models[1].advertisesTools)
        assertFalse(models[0].zeroPriced); assertEquals(false, models[0].advertisesTools)
        assertNull(models[2].advertisesTools); assertFalse(models[2].zeroPriced)
        assertNull(server.takeRequest().getHeader("Authorization")) // anonymous (key optional) sends no header
    }
}

class OpenAiCompatWireTest {
    @Test fun replaysHistoryAsChatMessages() {
        val req = AgentRequest("", listOf(AgentItem.User("q"), AgentItem.Assistant("thinking"), AgentItem.ToolCall("c1", "t", "{}"),
            AgentItem.ToolResult("c1", "out"), AgentItem.Opaque("gemini", "{}"), AgentItem.Assistant("done")), emptyList())
        val messages = OpenAiCompatWire.messages(req)
        assertEquals(4, messages.length())
        val assistant = messages.getJSONObject(1)
        assertEquals("thinking", assistant.getString("content"))
        assertEquals("c1", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(2).getString("role"))
        assertEquals("done", messages.getJSONObject(3).getString("content"))
    }

    @Test fun quirksControlOptionalParameters() {
        val req = AgentRequest("", listOf(AgentItem.User("q")), listOf(ToolSpec("t", "d", """{"type":"object"}""")))
        assertTrue(OpenAiCompatWire.body("m", req, CompatQuirks()).has("parallel_tool_calls"))
        val cohere = OpenAiCompatWire.body("m", req, CompatQuirks(parallelToolCallsParam = false, streamUsage = false))
        assertFalse(cohere.has("parallel_tool_calls")); assertFalse(cohere.has("stream_options"))
    }

    @Test fun freeRules() {
        val free = CompatModel("x:free", "x", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, null)
        assertTrue(OpenAiCompatWire.isFree(FreeRule.ZeroPriced(":free"), free, free.id))
        assertFalse(OpenAiCompatWire.isFree(FreeRule.ZeroPriced(":free"), free.copy(id = "x"), "x"))
        assertFalse(OpenAiCompatWire.isFree(FreeRule.ZeroPriced(), null, "x"))
        assertTrue(OpenAiCompatWire.isFree(FreeRule.Listed(setOf("glm-4.7-flash")), null, "glm-4.7-flash"))
        assertFalse(OpenAiCompatWire.isFree(FreeRule.Listed(setOf("glm-4.7-flash")), null, "glm-5"))
        assertTrue(OpenAiCompatWire.isFree(FreeRule.Matching(Regex("-free$")), null, "minimax-m3-free"))
    }
}

class ProviderRegistryTest {
    @Test fun registryIsConsistent() {
        val ids = ProviderRegistry.entries.map { it.descriptor.id }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse("GitHub Models is retired", ids.any { "github" in it })
        ProviderRegistry.entries.forEach { e ->
            assertTrue(e.descriptor.baseUrl, e.descriptor.baseUrl.startsWith("https://"))
            assertTrue(e.descriptor.sourceUrl.startsWith("https://"))
            assertNotEquals(AllowanceClass.Paid, e.descriptor.allowanceClass)
            if (e.descriptor.allowanceClass == AllowanceClass.AccountDependent) assertNotNull(e.needsPlanConfirmation)
        }
    }

    @Test fun customUrlRules() {
        assertTrue(ProviderRegistry.validCustomUrl("https://omniroute.example.com/v1"))
        assertTrue(ProviderRegistry.validCustomUrl("http://127.0.0.1:20128/v1"))
        assertFalse(ProviderRegistry.validCustomUrl("http://omniroute.example.com/v1"))
        assertFalse(ProviderRegistry.validCustomUrl("https://user:pass@example.com/v1"))
        assertFalse(ProviderRegistry.validCustomUrl("https://example.com/v1?key=x"))
        // Matches the cleartext policy (127.0.0.1 only) and uses a real URL parser.
        assertFalse(ProviderRegistry.validCustomUrl("http://localhost:20128/v1"))
        assertFalse(ProviderRegistry.validCustomUrl("https://example.com#frag"))
        assertFalse(ProviderRegistry.validCustomUrl("https://exa mple.com/v1"))
        assertFalse(ProviderRegistry.validCustomUrl("ftp://example.com/v1"))
        assertFalse(ProviderRegistry.validCustomUrl("https://example.com:99999/v1"))
        assertTrue(ProviderRegistry.validCustomUrl("HTTPS://Example.com:8443/v1"))
        assertEquals(AllowanceClass.Paid, ProviderRegistry.custom("https://x.example/v1", "").descriptor.allowanceClass)
    }
}
