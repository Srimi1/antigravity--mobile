package dev.srimi.antigravitymobile.providers

import dev.srimi.antigravitymobile.AgentRequest
import dev.srimi.antigravitymobile.ProviderEvent
import dev.srimi.antigravitymobile.ResponsesWire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Usage storage shared by providers. Lane A replaces [usage] with the Room implementation at startup. */
object ProviderStores {
    @Volatile var usage: ProviderUsageStore = InMemoryProviderUsageStore()
}

/**
 * Network core of the OpenAI-compatible adapter, independent of Android so it can be tested against a local
 * server. Blocking I/O; callers run it on Dispatchers.IO. The key is sent only as a Bearer header to [baseUrl].
 */
class CompatEngine(private val http: OkHttpClient, private val usage: () -> ProviderUsageStore = { ProviderStores.usage }) {
    @Volatile private var call: Call? = null
    private val catalogs = ConcurrentHashMap<String, Pair<Long, List<CompatModel>>>()

    fun cancel() { call?.cancel() }
    fun cached(id: String, maxAgeMs: Long = Long.MAX_VALUE): List<CompatModel>? =
        catalogs[id]?.takeIf { System.currentTimeMillis() - it.first <= maxAgeMs }?.second
    fun forget(id: String) { catalogs.remove(id) }

    private fun request(baseUrl: String, key: String?, path: String) = Request.Builder().url((baseUrl.trimEnd('/') + path).toHttpUrl())
        .apply { if (key != null) header("Authorization", "Bearer $key") }
    private fun execute(request: Request): Response { val pending = http.newCall(request); call = pending; return pending.execute() }

    fun catalog(entry: ProviderEntry, baseUrl: String, key: String?): List<CompatModel> = try {
        execute(request(baseUrl, key, "/models").build()).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure(response, body)
            val json = runCatching { JSONObject(body) }.getOrElse { throw ProviderFailure.Unknown("${entry.descriptor.displayName} returned an unreadable model list") }
            OpenAiCompatWire.models(json).also { catalogs[entry.descriptor.id] = System.currentTimeMillis() to it }
        }
    } finally { call = null }

    /**
     * One streamed turn. In free mode the model's current catalog price is checked first (refreshed after 30
     * minutes for price-based rules), so a pricing change stops the task before anything is sent.
     */
    fun turn(entry: ProviderEntry, baseUrl: String, key: String?, model: String, request: AgentRequest,
             freeOnly: Boolean, planConfirmed: Boolean): Flow<ProviderEvent> = flow {
        val id = entry.descriptor.id
        val catalog = if (freeOnly && entry.freeRule is FreeRule.ZeroPriced) cached(id, 30 * 60_000L) ?: catalog(entry, baseUrl, key) else cached(id).orEmpty()
        FreeModePolicy.check(entry, model, catalog.firstOrNull { it.id == model }, freeOnly, planConfirmed)?.let { throw it }
        val body = OpenAiCompatWire.body(model, request, entry.quirks)
        val response = execute(request(baseUrl, key, "/chat/completions").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        try {
            response.use {
                if (!it.isSuccessful) throw failure(it, it.peekBody(65_536).string())
                val parser = CompatStreamParser(entry.descriptor.displayName)
                val reader = it.body?.charStream()?.buffered() ?: throw ProviderFailure.StreamInterrupted("Empty reply from ${entry.descriptor.displayName}")
                var line = reader.readLine()
                while (line != null) { parser.line(line).forEach { event -> emit(event) }; if (parser.done) break; line = reader.readLine() }
                val tail = parser.finish()
                usage().record(UsageRecord(id, model, null, parser.usage?.inputTokens, parser.usage?.outputTokens, 1, System.currentTimeMillis()))
                tail.forEach { event -> emit(event) }
            }
        } finally { call = null }
    }

    private fun failure(response: Response, body: String): ProviderFailure =
        ResponsesWire.failure(response.code, response.header("Content-Type").orEmpty(), body.take(65_536), response.header("Retry-After"))
}
