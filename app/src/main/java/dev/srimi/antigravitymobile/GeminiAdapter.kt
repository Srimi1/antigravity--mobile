package dev.srimi.antigravitymobile

import android.content.Context
import dev.srimi.antigravitymobile.network.AndroidNetworkDiagnostics
import dev.srimi.antigravitymobile.providers.FailureClassifier
import dev.srimi.antigravitymobile.providers.ProviderFailure
import dev.srimi.antigravitymobile.providers.asProviderFailures
import dev.srimi.antigravitymobile.providers.providerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Gemini API through the user's own Google AI Studio API key (documented, key-based; not a Google AI subscription).
 * The key stays in Keystore-encrypted storage and is sent only to generativelanguage.googleapis.com.
 */
class GeminiAdapter(context: Context) : AgentModel {
    override val providerId = GeminiWire.PROVIDER
    private val credentials = CredentialStore(context, "gemini.credentials")
    private val prefs = context.getSharedPreferences("gemini", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val lock = Mutex()
    private val diagnostics = AndroidNetworkDiagnostics.shared(context)
    @Volatile private var call: Call? = null
    @Volatile private var defaultModel: Pair<Long, String>? = null

    private fun key(): String = credentials.read()?.optString("api_key").orEmpty().ifEmpty { throw ProviderFailure.AuthInvalid("Add your Google AI Studio API key in Accounts") }
    fun hasKey(): Boolean = runCatching { credentials.read()?.optString("api_key").orEmpty().isNotEmpty() }.getOrDefault(false)

    /** Validates the key with a model listing before saving it. */
    suspend fun saveKey(raw: String): List<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val value = raw.trim()
            require(value.length in 20..200 && value.none { it.isWhitespace() }) { "That does not look like an API key" }
            val models = providerCall(GeminiWire.HOST, "Google", diagnostics) { catalog(value) }
            credentials.save(JSONObject().put("api_key", value))
            prefs.edit().remove("verifiedAt").apply()
            models
        }
    }
    suspend fun removeKey() = withContext(Dispatchers.IO) {
        credentials.save(JSONObject()); prefs.edit().remove("verifiedAt").apply()
    }
    suspend fun listModels(): List<String> = lock.withLock { withContext(Dispatchers.IO) { providerCall(GeminiWire.HOST, "Google", diagnostics) { catalog(key()) } } }
    var preferredModel: String?
        get() = prefs.getString("model", null)
        set(value) { prefs.edit().apply { if (value == null) remove("model") else putString("model", value) }.apply() }

    private fun execute(request: Request): Response { val pending = http.newCall(request); call = pending; return pending.execute() }
    private fun catalog(key: String): List<String> {
        val names = mutableListOf<String>(); var page = ""
        do {
            val url = HttpUrl.Builder().scheme("https").host(GeminiWire.HOST).addPathSegments("v1beta/models")
                .addQueryParameter("pageSize", "1000").apply { if (page.isNotEmpty()) addQueryParameter("pageToken", page) }.build()
            val json = execute(Request.Builder().url(url).header("x-goog-api-key", key).build()).use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw GeminiWire.failure(it.code, body, it.header("Retry-After"))
                JSONObject(body)
            }
            names += GeminiWire.models(json)
            page = json.optString("nextPageToken")
        } while (page.isNotEmpty() && names.size < 5000)
        call = null
        return GeminiWire.ordered(names)
    }

    override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow {
        lock.withLock {
            val key = key()
            val model = preferredModel ?: defaultModel?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.second
                ?: (catalog(key).firstOrNull() ?: error("No Gemini model is available for this key")).also { defaultModel = System.currentTimeMillis() to it }
            val url = HttpUrl.Builder().scheme("https").host(GeminiWire.HOST)
                .addPathSegments("v1beta/models/${model.removePrefix("models/")}:streamGenerateContent").addQueryParameter("alt", "sse").build()
            val response = execute(Request.Builder().url(url).header("x-goog-api-key", key)
                .post(GeminiWire.body(request).toString().toRequestBody("application/json".toMediaType())).build())
            try {
                response.use {
                    if (!it.isSuccessful) throw GeminiWire.failure(it.code, it.peekBody(65_536).string(), it.header("Retry-After"))
                    val reader = it.body?.charStream()?.buffered() ?: error("Empty Gemini stream")
                    val parser = GeminiStreamParser()
                    var line = reader.readLine()
                    while (line != null) { parser.line(line).forEach { event -> emit(event) }; line = reader.readLine() }
                    parser.finish().forEach { event -> emit(event) }
                    prefs.edit().putLong("verifiedAt", System.currentTimeMillis()).apply()
                }
            } finally { call = null }
        }
    }.asProviderFailures(GeminiWire.HOST, "Google", diagnostics).flowOn(Dispatchers.IO)

    override fun cancel() { call?.cancel() }

    fun accountState(): AccountState {
        if (!hasKey()) return AccountState(ProviderId.GEMINI, AccountStatus.DISCONNECTED,
            "Not set up. Create a key at aistudio.google.com/apikey and paste it here.")
        val verifiedAt = prefs.getLong("verifiedAt", 0)
        return if (verifiedAt > 0) AccountState(ProviderId.GEMINI, AccountStatus.VERIFIED,
            "API key works. Last response ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(verifiedAt))}.")
        else AccountState(ProviderId.GEMINI, AccountStatus.CONNECTED, "API key saved and accepted by Google. Send a test request.")
    }
}

/** Gemini API request/response mapping. Pure functions so they can be tested on the JVM. */
object GeminiWire {
    const val PROVIDER = "gemini"
    const val HOST = "generativelanguage.googleapis.com"
    private const val LOCAL_ID = "local-"

    fun models(json: JSONObject): List<String> {
        val models = json.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).map { models.getJSONObject(it) }.filter { model ->
            val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            (0 until methods.length()).any { methods.getString(it) == "generateContent" }
        }.map { it.getString("name").removePrefix("models/") }.filter { it.startsWith("gemini") }
    }
    /** Stable models first, preferring Flash (fast, free-tier friendly), then Pro; previews and special variants last. */
    fun ordered(names: List<String>): List<String> = names.distinct().sortedWith(compareBy<String>(
        { if (Regex("preview|exp|tts|image|audio|live|embedding|thinking|computer").containsMatchIn(it)) 1 else 0 },
        { when { "flash-lite" in it -> 1; "flash" in it -> 0; "pro" in it -> 2; else -> 3 } },
        { -(Regex("gemini-(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0) },
        { it }))

    fun body(request: AgentRequest): JSONObject = JSONObject().put("contents", contents(request.input)).apply {
        if (request.instructions.isNotBlank()) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.instructions))))
        if (request.tools.isNotEmpty()) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
            request.tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description)
                .put("parametersJsonSchema", JSONObject(it.parametersJson))) }
        })))
    }

    /**
     * Converts provider-neutral items. A model turn recorded by this adapter is replayed verbatim from its Opaque
     * item (keeping thought signatures that Gemini requires for function calling); the Assistant/ToolCall copies
     * that follow it are skipped. Tool results are grouped into one user turn.
     */
    fun contents(items: List<AgentItem>): JSONArray {
        val out = JSONArray()
        val names = items.filterIsInstance<AgentItem.ToolCall>().associate { it.callId to it.name }
        var replayed = false
        var pendingResults: JSONArray? = null
        fun flushResults() { pendingResults?.let { out.put(JSONObject().put("role", "user").put("parts", it)) }; pendingResults = null }
        for (item in items) {
            if (item !is AgentItem.ToolResult) flushResults()
            when (item) {
                is AgentItem.User -> { replayed = false; out.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", item.text)))) }
                is AgentItem.Opaque -> if (item.provider == PROVIDER) { replayed = true; out.put(JSONObject(item.json)) }
                is AgentItem.Assistant -> if (!replayed && item.text.isNotEmpty())
                    out.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", item.text))))
                is AgentItem.ToolCall -> if (!replayed) out.put(JSONObject().put("role", "model").put("parts", JSONArray().put(
                    JSONObject().put("functionCall", call(item)))))
                is AgentItem.ToolResult -> {
                    replayed = false
                    val response = JSONObject().put("name", names[item.callId] ?: "tool").put("response", JSONObject().put("output", item.output))
                    if (!item.callId.startsWith(LOCAL_ID)) response.put("id", item.callId)
                    (pendingResults ?: JSONArray().also { pendingResults = it }).put(JSONObject().put("functionResponse", response))
                }
            }
        }
        flushResults()
        return out
    }
    private fun call(item: AgentItem.ToolCall) = JSONObject().put("name", item.name)
        .put("args", runCatching { JSONObject(item.arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())).apply {
            if (!item.callId.startsWith(LOCAL_ID)) put("id", item.callId)
        }

    /**
     * Typed failure. Google answers 429 RESOURCE_EXHAUSTED both for per-minute limits (with a RetryInfo delay)
     * and for exhausted per-day free-tier quota (QuotaFailure ids containing "PerDay").
     */
    fun failure(status: Int, body: String, retryAfter: String? = null): ProviderFailure {
        val error = runCatching { JSONObject(body.trim()).optJSONObject("error") }.getOrNull()
        val details = error?.optJSONArray("details") ?: JSONArray()
        var delay: Long? = FailureClassifier.retryAfter(retryAfter)
        var daily = false
        for (i in 0 until details.length()) {
            val detail = details.optJSONObject(i) ?: continue
            detail.optString("retryDelay").removeSuffix("s").toDoubleOrNull()?.let { delay = it.toLong().coerceAtLeast(1) }
            val violations = detail.optJSONArray("violations") ?: continue
            for (j in 0 until violations.length()) {
                if (Regex("(?i)per ?day").containsMatchIn(violations.optJSONObject(j)?.optString("quotaId").orEmpty())) daily = true
            }
        }
        val reason = error?.optString("status").orEmpty() + " " + details.toString().let { d -> Regex("\"reason\"\\s*:\\s*\"([A-Z_]+)\"").find(d)?.groupValues?.get(1).orEmpty() }
        return FailureClassifier.http(status, describe(status, body), reason.trim(), delay, dailyQuota = daily)
    }

    fun describe(status: Int, body: String): String {
        val error = runCatching { JSONObject(body.trim()).optJSONObject("error") }.getOrNull()
        val reason = error?.optString("status").orEmpty().takeIf { it.matches(Regex("[A-Z_]{1,40}")) }
        val message = error?.optString("message").orEmpty().replace(Regex("\\s+"), " ")
            .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "[key]").take(200).trimEnd('.', ' ')
        val hint = when (status) {
            400 -> if ("API key" in message) " Check the key in Accounts." else ""
            401, 403 -> " Check that the key is valid and the Gemini API is enabled for it."
            429 -> " The key's rate limit or free-tier quota is used up; wait or check AI Studio."
            else -> ""
        }
        return "Google replied HTTP $status" + (reason?.let { " $it" } ?: "") + (if (message.isNotBlank()) " — $message" else "") + "." + hint
    }
}

/** Parses `alt=sse` chunks of GenerateContentResponse into provider-neutral events. */
class GeminiStreamParser {
    private val parts = JSONArray()
    private val calls = mutableListOf<AgentItem.ToolCall>()
    private val text = StringBuilder()
    private var finish = ""
    private val data = StringBuilder()

    fun line(line: String): List<ProviderEvent> {
        if (line.isEmpty()) { if (data.isEmpty()) return emptyList(); val raw = data.toString(); data.clear(); return chunk(raw) }
        if (line.startsWith("data:")) {
            if (data.length + line.length > 4_000_000) throw ProviderFailure.Unknown("Gemini event exceeded the size limit")
            data.append(line.removePrefix("data:").trimStart())
        }
        return emptyList()
    }

    fun finish(): List<ProviderEvent> {
        val tail = line("")
        if (finish.isEmpty()) throw ProviderFailure.StreamInterrupted("The Gemini stream ended before the response finished")
        if (finish !in setOf("STOP", "MAX_TOKENS") && calls.isEmpty())
            throw ProviderFailure.Rejected("Gemini stopped the response ($finish)")
        val items = mutableListOf<ProviderEvent>()
        items += ProviderEvent.Item(AgentItem.Opaque(GeminiWire.PROVIDER, JSONObject().put("role", "model").put("parts", parts).toString()))
        if (text.isNotEmpty()) items += ProviderEvent.Item(AgentItem.Assistant(text.toString()))
        calls.forEach { items += ProviderEvent.Item(it) }
        return tail + items + ProviderEvent.Completed
    }

    private fun chunk(raw: String): List<ProviderEvent> {
        val json = JSONObject(raw)
        json.optJSONObject("error")?.let { throw GeminiWire.failure(it.optInt("code", 0), JSONObject().put("error", it).toString()) }
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            throw ProviderFailure.Rejected("Gemini blocked the request ($it)")
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return emptyList()
        val events = mutableListOf<ProviderEvent>()
        val content = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            parts.put(part)
            val function = part.optJSONObject("functionCall")
            when {
                function != null -> calls += AgentItem.ToolCall(function.optString("id").ifEmpty { "local-${UUID.randomUUID()}" },
                    function.getString("name"), (function.optJSONObject("args") ?: JSONObject()).toString())
                part.optBoolean("thought") -> Unit
                part.has("text") -> { val delta = part.getString("text"); text.append(delta); if (delta.isNotEmpty()) events += ProviderEvent.Text(delta) }
            }
        }
        candidate.optString("finishReason").takeIf { it.isNotEmpty() && it != "FINISH_REASON_UNSPECIFIED" }?.let { finish = it }
        return events
    }
}
