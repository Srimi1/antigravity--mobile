package dev.srimi.antigravitymobile.providers

import dev.srimi.antigravitymobile.bridge.MemoryPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real account API and HTTP engine; only Android preferences, Keystore and diagnostics are fixtures. */
class CompatProvidersTest {
    private suspend fun withProviders(usage: ProviderUsageStore = InMemoryProviderUsageStore(),
        test: suspend (CompatProviders, MockWebServer, ProviderUsageStore) -> Unit) {
        val server = MockWebServer().apply { start() }
        val credentials = mutableMapOf<String, JSONObject>()
        val previousUsage = ProviderStores.usage
        ProviderStores.usage = usage
        val providers = CompatProviders(MemoryPreferences(), object : NetworkDiagnostics {
            override suspend fun diagnose(host: String, port: Int): DiagnosticReport = error("successful fixture calls need no diagnostic")
        }, CompatEngine(OkHttpClient()), { id -> credentials[id] }, { id, value -> credentials[id] = value })
        try { test(providers, server, usage) }
        finally { ProviderStores.usage = previousUsage; server.shutdown() }
    }

    private fun catalog() = MockResponse().setBody("""{"data":[{"id":"same-model"}]}""")
    private fun toolCall() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: " + """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"ready-call","type":"function","function":{"name":"report_ready","arguments":"{\"ready\":true}"}}]},"finish_reason":"tool_calls"}]}""" +
            "\n\ndata: [DONE]\n\n")

    @Test fun changingCustomEndpointRequiresItsOwnToolVerification() = runBlocking {
        withProviders { providers, server, usage ->
            val id = ProviderRegistry.CUSTOM
            providers.setCustom(server.url("/endpoint-a/v1").toString(), "A")
            server.enqueue(catalog())
            providers.saveKey(id, "fixture-key-only")
            providers.freeOnly = false
            providers.setModel(id, "same-model")
            providers.setPlanConfirmed(id, true)
            server.enqueue(toolCall())
            assertTrue(providers.verifyToolCalling(id, "same-model"))
            assertNotNull(providers.verifiedAt(id, "same-model"))
            assertTrue(usage.models(id).single().toolCallingVerified)

            providers.setCustom(server.url("/endpoint-b/v1").toString(), "B")
            assertNull("endpoint B never passed the check", providers.verifiedAt(id, "same-model"))
            assertFalse(providers.hasKey(id))
            assertNull(providers.model(id))
            assertFalse(providers.planConfirmed(id))
            assertTrue(providers.cachedModels(id).isEmpty())
            assertFalse("stored evidence also loses its active verification", usage.models(id).single().toolCallingVerified)

            // Even when B advertises the same model ID, listing its models cannot restore A's verification.
            server.enqueue(catalog())
            providers.saveKey(id, "new-fixture-key")
            assertNull(providers.verifiedAt(id, "same-model"))
            server.enqueue(toolCall())
            assertTrue(providers.verifyToolCalling(id, "same-model"))
            assertTrue(usage.models(id).single().toolCallingVerified)
        }
    }

    @Test fun endpointChangeWaitsForEntireVerificationIncludingEvidence() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backing = InMemoryProviderUsageStore()
        val usage = object : ProviderUsageStore by backing {
            override suspend fun recordModel(verification: ModelVerification) {
                if (verification.toolCallingVerified) { writing.complete(Unit); release.await() }
                backing.recordModel(verification)
            }
        }
        withProviders(usage) { providers, server, _ ->
            val id = ProviderRegistry.CUSTOM
            providers.setCustom(server.url("/a/v1").toString(), "A")
            providers.freeOnly = false
            server.enqueue(toolCall())
            val probe = async(Dispatchers.IO) { providers.verifyToolCalling(id, "same-model") }
            withTimeout(5_000) { writing.await() }
            val changing = CompletableDeferred<Unit>()
            val change = async(Dispatchers.IO) {
                changing.complete(Unit)
                providers.setCustom(server.url("/b/v1").toString(), "B")
            }
            try {
                changing.await()
                assertNull("endpoint cannot change while its probe evidence is being recorded",
                    withTimeoutOrNull(200) { change.await() })
                release.complete(Unit)
                assertTrue(probe.await())
                change.await()
                assertNull(providers.verifiedAt(id, "same-model"))
                assertFalse(usage.models(id).single().toolCallingVerified)
            } finally { release.complete(Unit); probe.await(); change.await() }
        }
    }
}
