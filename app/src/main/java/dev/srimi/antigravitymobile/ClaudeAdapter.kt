package dev.srimi.antigravitymobile

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.srimi.antigravitymobile.network.AndroidNetworkDiagnostics
import dev.srimi.antigravitymobile.providers.FailureClassifier
import dev.srimi.antigravitymobile.providers.ProviderFailure
import dev.srimi.antigravitymobile.providers.asProviderFailures
import dev.srimi.antigravitymobile.providers.providerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.Collections
import java.util.UUID

data class ClaudeModel(val id: String, val displayName: String)

/**
 * Claude through the user's own Anthropic API key (official Java SDK). Billed per use to the user's Anthropic
 * Console account; this is not a Claude Pro/Max subscription. The key stays in Keystore-encrypted storage.
 */
class ClaudeAdapter(context: Context) : AgentModel {
    override val providerId = PROVIDER
    private val credentials = CredentialStore(context, "claude.credentials")
    private val prefs = context.getSharedPreferences("claude", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val diagnostics = AndroidNetworkDiagnostics.shared(context)
    private fun key(): String = credentials.read()?.optString("api_key").orEmpty().ifEmpty { throw ProviderFailure.AuthInvalid("Add your Anthropic API key in Accounts") }
    fun hasKey(): Boolean = runCatching { credentials.read()?.optString("api_key").orEmpty().isNotEmpty() }.getOrDefault(false)
    private val engine = ClaudeEngine { key -> AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofMinutes(10)).maxRetries(2).build() }

    var preferredModel: String?
        get() = prefs.getString("model", null)
        set(value) { prefs.edit().apply { if (value == null) remove("model") else putString("model", value) }.apply() }

    /** Validates the key by listing models, then saves it. */
    suspend fun saveKey(raw: String): List<ClaudeModel> = lock.withLock {
        withContext(Dispatchers.IO) {
            val value = raw.trim()
            require(value.startsWith("sk-ant-") && value.none { it.isWhitespace() }) { "That does not look like an Anthropic API key (it starts with sk-ant-)" }
            val models = providerCall(HOST, "Anthropic", diagnostics) { engine.catalog(value) }
            credentials.save(JSONObject().put("api_key", value))
            prefs.edit().remove("verifiedAt").apply()
            models
        }
    }
    suspend fun removeKey() = withContext(Dispatchers.IO) { credentials.save(JSONObject()); prefs.edit().remove("verifiedAt").apply() }
    suspend fun listModels(): List<ClaudeModel> = lock.withLock { withContext(Dispatchers.IO) { providerCall(HOST, "Anthropic", diagnostics) { engine.catalog(key()) } } }

    override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow {
        lock.withLock {
            engine.turn(key(), preferredModel ?: DEFAULT_MODEL, request).collect { emit(it) }
            prefs.edit().putLong("verifiedAt", System.currentTimeMillis()).apply()
        }
    }.asProviderFailures(HOST, "Anthropic", diagnostics).flowOn(Dispatchers.IO)

    override fun cancel() = engine.cancel()

    fun accountState(): AccountState {
        if (!hasKey()) return AccountState(ProviderId.CLAUDE_KEY, AccountStatus.DISCONNECTED,
            "Not set up. Create a key at console.anthropic.com and paste it here. Paid per use.")
        val verifiedAt = prefs.getLong("verifiedAt", 0)
        return if (verifiedAt > 0) AccountState(ProviderId.CLAUDE_KEY, AccountStatus.VERIFIED,
            "API key works. Last response ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(verifiedAt))}.")
        else AccountState(ProviderId.CLAUDE_KEY, AccountStatus.CONNECTED, "API key saved and accepted by Anthropic. Send a test request.")
    }

    companion object {
        const val PROVIDER = "claude"
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val HOST = "api.anthropic.com"
    }
}

/** Network core on the official SDK; independent of Android so it can be tested against a local server. */
class ClaudeEngine(private val newClient: (String) -> AnthropicClient) {
    /** Assistant turns exactly as returned (thinking blocks included), replayed unchanged within a task. */
    private val turns = Collections.synchronizedMap(object : LinkedHashMap<String, List<BetaContentBlockParam>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<BetaContentBlockParam>>?) = size > 200
    })

    @Volatile private var active: com.anthropic.core.http.StreamResponse<*>? = null
    fun cancel() { runCatching { active?.close() } }
    private fun explained(error: AnthropicServiceException) = ClaudeWire.failure(error.statusCode(), error.message.orEmpty(),
        runCatching { error.headers().values("retry-after").firstOrNull() }.getOrNull())

    fun catalog(key: String): List<ClaudeModel> {
        val client = newClient(key)
        try {
            val models = mutableListOf<ClaudeModel>()
            // One page is enough: Anthropic lists a few dozen models at most.
            val page = client.models().list(com.anthropic.models.models.ModelListParams.builder().limit(1000L).build())
            for (info in page.data()) models += ClaudeModel(info.id(), info.displayName())
            return ClaudeWire.ordered(models)
        } catch (error: AnthropicServiceException) { throw explained(error) } finally { client.close() }
    }

    fun turn(key: String, model: String, request: AgentRequest): Flow<ProviderEvent> = flow {
        run {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(64_000L)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                .messages(messages(request.input))
                // Server-side refusal fallback: if the model declines, Anthropic retries on a suitable model.
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            if (request.instructions.isNotBlank()) builder.system(request.instructions)
            for (spec in request.tools) builder.addTool(tool(spec))
            val params = builder.build()
            val client = newClient(key)
            val accumulator = BetaMessageAccumulator.create()
            try {
                val stream = try { client.beta().messages().createStreaming(params) } catch (error: AnthropicServiceException) { throw explained(error) }
                active = stream
                try {
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        val event = accumulator.accumulate(events.next())
                        val delta = event.contentBlockDelta().orElse(null)?.delta()?.text()?.orElse(null)?.text()
                        if (!delta.isNullOrEmpty()) emit(ProviderEvent.Text(delta))
                    }
                } catch (error: AnthropicServiceException) { throw explained(error) } finally { active = null; stream.close() }
            } finally { client.close() }
            val message = accumulator.message()
            val stop = message.stopReason().orElse(null)?.toString().orEmpty()
            if (stop == "refusal") throw ProviderFailure.Rejected("Claude declined this request (refusal)")
            val id = "claude-${UUID.randomUUID()}"
            turns[id] = message.content().map { it.toParam() }
            emit(ProviderEvent.Item(AgentItem.Opaque(ClaudeAdapter.PROVIDER, id)))
            val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            if (text.isNotEmpty()) emit(ProviderEvent.Item(AgentItem.Assistant(text)))
            for (block in message.content()) {
                val use = block.toolUse().orElse(null) ?: continue
                val input = use._input().convert(Map::class.java) ?: emptyMap<String, Any?>()
                emit(ProviderEvent.Item(AgentItem.ToolCall(use.id(), use.name(), ClaudeWire.normalizeJson(JSONObject(input).toString()))))
            }
            if (stop == "max_tokens") throw ProviderFailure.Rejected("Claude's reply hit the length limit before finishing")
            emit(ProviderEvent.Completed)
        }
    }.flowOn(Dispatchers.IO)


    /** Provider-neutral items to Messages API turns. Tool results are grouped in one user turn. */
    private fun messages(items: List<AgentItem>): List<BetaMessageParam> {
        val out = mutableListOf<BetaMessageParam>()
        val blocks = ClaudeWire.plan(items) { id -> turns.containsKey(id) }
        blocks.forEach { turn ->
            val content = turn.parts.flatMap { part ->
                when (part) {
                    is ClaudeWire.Part.Stored -> turns[part.id].orEmpty()
                    is ClaudeWire.Part.Text -> listOf(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(part.text).build()))
                    is ClaudeWire.Part.Call -> listOf(BetaContentBlockParam.ofToolUse(BetaToolUseBlockParam.builder()
                        .id(part.id).name(part.name).input(JsonValue.from(ClaudeWire.toMap(part.arguments))).build()))
                    is ClaudeWire.Part.Result -> listOf(BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                        .toolUseId(part.id).content(part.output).build()))
                }
            }
            out += BetaMessageParam.builder().role(if (turn.assistant) BetaMessageParam.Role.ASSISTANT else BetaMessageParam.Role.USER)
                .contentOfBetaContentBlockParams(content).build()
        }
        return out
    }

    private fun tool(spec: ToolSpec): BetaTool {
        val schema = JSONObject(spec.parametersJson)
        val properties = BetaTool.InputSchema.Properties.builder().apply {
            schema.optJSONObject("properties")?.let { props -> props.keys().forEach { name ->
                putAdditionalProperty(name, JsonValue.from(ClaudeWire.toMap(props.getJSONObject(name).toString())))
            } }
        }.build()
        val required = schema.optJSONArray("required")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
        return BetaTool.builder().name(spec.name).description(spec.description)
            .inputSchema(BetaTool.InputSchema.builder().properties(properties).required(required)
                .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
            .build()
    }


}

/** Pure mapping helpers, testable on the JVM. */
object ClaudeWire {
    sealed interface Part {
        data class Stored(val id: String) : Part
        data class Text(val text: String) : Part
        data class Call(val id: String, val name: String, val arguments: String) : Part
        data class Result(val id: String, val output: String) : Part
    }
    data class Turn(val assistant: Boolean, val parts: MutableList<Part> = mutableListOf())

    /**
     * Groups items into alternating turns. A stored assistant turn replaces the Assistant/ToolCall copies that
     * follow it; when it is gone (app restarted) those copies are sent instead, without thinking blocks.
     */
    fun plan(items: List<AgentItem>, stored: (String) -> Boolean): List<Turn> {
        val turns = mutableListOf<Turn>()
        var replayed = false
        fun turn(assistant: Boolean): Turn = turns.lastOrNull()?.takeIf { it.assistant == assistant } ?: Turn(assistant).also { turns += it }
        for (item in items) when (item) {
            is AgentItem.User -> { replayed = false; turn(false).parts += Part.Text(item.text) }
            is AgentItem.Opaque -> if (item.provider == ClaudeAdapter.PROVIDER && stored(item.json)) { replayed = true; turn(true).parts += Part.Stored(item.json) }
            is AgentItem.Assistant -> if (!replayed && item.text.isNotEmpty()) turn(true).parts += Part.Text(item.text)
            is AgentItem.ToolCall -> if (!replayed) turn(true).parts += Part.Call(item.callId, item.name, item.arguments)
            is AgentItem.ToolResult -> { replayed = false; turn(false).parts += Part.Result(item.callId, item.output) }
        }
        return turns
    }

    /** Opus first (most capable default), then Sonnet, then Haiku; newer versions first within each family. */
    fun ordered(models: List<ClaudeModel>): List<ClaudeModel> = models.distinctBy { it.id }.sortedWith(compareBy<ClaudeModel>(
        { when { "opus" in it.id -> 0; "fable" in it.id -> 1; "sonnet" in it.id -> 2; "haiku" in it.id -> 3; else -> 4 } },
        { -(Regex("(\\d+)(?:-(\\d))?(?!\\d)").findAll(it.id).firstOrNull()?.let { m ->
            m.groupValues[1].toDouble() + (m.groupValues[2].toDoubleOrNull() ?: 0.0) / 10 } ?: 0.0) },
        { it.id }))

    fun failure(status: Int, message: String, retryAfter: String? = null): ProviderFailure {
        val json = message.indexOf('{').takeIf { it >= 0 }?.let { runCatching { JSONObject(message.substring(it)) }.getOrNull() }
        val type = json?.optJSONObject("error")?.optString("type").orEmpty().take(64)
        return FailureClassifier.http(status, describe(status, message), type, FailureClassifier.retryAfter(retryAfter))
    }

    fun describe(status: Int, message: String): String {
        // SDK messages look like `401: {"type":"error","error":{"type":...,"message":...}}`; keep only the provider's text.
        val json = message.indexOf('{').takeIf { it >= 0 }?.let { runCatching { JSONObject(message.substring(it)) }.getOrNull() }
        val text = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: message
        val clean = text.replace(Regex("sk-ant-[A-Za-z0-9_-]+"), "[key]").replace(Regex("\\s+"), " ").take(220)
        val hint = when (status) {
            401 -> " The API key is invalid; check it in Accounts."
            402, 403 -> " The key's organization has no credit or no access; check console.anthropic.com."
            429 -> " Rate limit or spend limit reached; wait or raise limits in the Console."
            529 -> " Anthropic is overloaded; try again shortly."
            else -> ""
        }
        return "Anthropic replied HTTP $status — $clean.$hint".replace("..", ".")
    }

    /** JSON text to plain Maps/Lists for the SDK's JsonValue. */
    fun toMap(json: String): Any? = convert(runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrElse { JSONObject() })
    private fun convert(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { convert(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { convert(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
    /** Tool inputs as compact JSON objects; anything else becomes an empty object so the tool reports a clear error. */
    fun normalizeJson(raw: String): String = runCatching { JSONObject(raw).toString() }.getOrDefault("{}")
}
