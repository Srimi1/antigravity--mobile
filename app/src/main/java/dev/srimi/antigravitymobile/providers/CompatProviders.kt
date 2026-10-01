package dev.srimi.antigravitymobile.providers

import android.content.Context
import dev.srimi.antigravitymobile.AccountState
import dev.srimi.antigravitymobile.AccountStatus
import dev.srimi.antigravitymobile.AgentItem
import dev.srimi.antigravitymobile.AgentModel
import dev.srimi.antigravitymobile.AgentRequest
import dev.srimi.antigravitymobile.CredentialStore
import dev.srimi.antigravitymobile.ProviderEvent
import dev.srimi.antigravitymobile.ProviderId
import dev.srimi.antigravitymobile.ToolSpec
import dev.srimi.antigravitymobile.network.AndroidNetworkDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Free/trial providers and a custom endpoint through one OpenAI-compatible adapter. The user picks one provider
 * and one model; there is no automatic routing or fallback. Each provider's key is stored separately and sent
 * only to that provider's base URL.
 */
class CompatProviders(context: Context) : AgentModel {
    override val providerId = "compat"
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("compat", Context.MODE_PRIVATE)
    private val diagnostics = AndroidNetworkDiagnostics.shared(app)
    private val engine = CompatEngine(OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build())
    private val lock = Mutex()

    // ---- settings (no secrets) ----
    var selected: String?
        get() = prefs.getString("selected", null)
        set(value) { prefs.edit().apply { if (value == null) remove("selected") else putString("selected", value) }.apply() }
    /** Free mode is on unless the user turned it off. */
    var freeOnly: Boolean
        get() = prefs.getBoolean("freeOnly", true)
        set(value) { prefs.edit().putBoolean("freeOnly", value).apply() }
    fun model(id: String): String? = prefs.getString("model.$id", null)
    fun setModel(id: String, model: String?) { prefs.edit().apply { if (model == null) remove("model.$id") else putString("model.$id", model) }.apply() }
    fun planConfirmed(id: String) = prefs.getBoolean("plan.$id", false)
    fun setPlanConfirmed(id: String, value: Boolean) { prefs.edit().putBoolean("plan.$id", value).apply() }
    var cloudflareAccount: String
        get() = prefs.getString("cloudflareAccount", "").orEmpty()
        set(value) { prefs.edit().putString("cloudflareAccount", value.trim()).apply() }
    val customUrl: String get() = prefs.getString("customUrl", "").orEmpty()
    val customName: String get() = prefs.getString("customName", "").orEmpty()

    fun entries(): List<ProviderEntry> = ProviderRegistry.entries + listOfNotNull(customUrl.takeIf { it.isNotEmpty() }?.let { ProviderRegistry.custom(it, customName) })
    fun entry(id: String): ProviderEntry? = entries().firstOrNull { it.descriptor.id == id }

    // ---- credentials (Keystore-encrypted, one record per provider) ----
    private fun store(id: String) = CredentialStore(app, "compat-$id.credentials")
    private fun key(id: String): String? = runCatching { store(id).read()?.optString("api_key") }.getOrNull()?.takeIf { it.isNotEmpty() }
    fun hasKey(id: String) = key(id) != null

    /** Checks the key by listing models, then saves it. For the custom endpoint the key is bound to [customUrl]. */
    suspend fun saveKey(id: String, raw: String): List<CompatModel> = lock.withLock {
        withContext(Dispatchers.IO) {
            val entry = entry(id) ?: error("Unknown provider")
            val value = raw.trim()
            require(value.length in 8..400 && value.none { it.isWhitespace() }) { "That does not look like an API key" }
            val models = fetchCatalog(entry, value)
            store(id).save(JSONObject().put("api_key", value).put("base_url", entry.descriptor.baseUrl))
            models
        }
    }
    suspend fun removeKey(id: String) = withContext(Dispatchers.IO) { store(id).save(JSONObject()); engine.forget(id) }

    /** Sets the custom endpoint. Changing the URL erases the key saved for the previous URL. */
    suspend fun setCustom(url: String, name: String) = withContext(Dispatchers.IO) {
        val clean = url.trim().trimEnd('/')
        require(ProviderRegistry.validCustomUrl(clean)) { "Use an https:// URL (http:// only for 127.0.0.1 on this phone), without credentials or query" }
        if (clean != customUrl) { store(ProviderRegistry.CUSTOM).save(JSONObject()); engine.forget(ProviderRegistry.CUSTOM) }
        prefs.edit().putString("customUrl", clean).putString("customName", name.trim().take(60)).apply()
    }

    private fun baseUrl(entry: ProviderEntry): String {
        val base = entry.descriptor.baseUrl
        if (!entry.quirks.needsAccountId) return base
        val account = cloudflareAccount
        require(account.matches(Regex("[0-9a-f]{32}"))) { "Enter your 32-character Cloudflare account ID first" }
        return base.replace("{account_id}", account)
    }
    private fun keyFor(entry: ProviderEntry): String? {
        val saved = runCatching { store(entry.descriptor.id).read() }.getOrNull()
        val key = saved?.optString("api_key").orEmpty()
        // A key is only ever sent to the base URL it was saved for.
        if (key.isNotEmpty() && saved?.optString("base_url") != entry.descriptor.baseUrl) return null
        return key.ifEmpty { null }
    }
    private suspend fun fetchCatalog(entry: ProviderEntry, key: String?): List<CompatModel> =
        providerCall(entry.descriptor.host, entry.descriptor.displayName, diagnostics) { engine.catalog(entry, baseUrl(entry), key) }

    suspend fun listModels(id: String): List<CompatModel> = lock.withLock {
        withContext(Dispatchers.IO) {
            val entry = entry(id) ?: error("Unknown provider")
            fetchCatalog(entry, keyFor(entry) ?: if (entry.quirks.keyOptional) null else throw ProviderFailure.AuthInvalid("Add a ${entry.descriptor.displayName} key first"))
        }
    }
    fun cachedModels(id: String): List<CompatModel> = engine.cached(id).orEmpty()

    // ---- tool-calling verification ----
    fun verifiedAt(id: String, model: String): Long? = prefs.getLong("verified.$id/$model", 0).takeIf { it > 0 }
    fun failedAt(id: String, model: String): Long? = prefs.getLong("failed.$id/$model", 0).takeIf { it > 0 }

    /** Sends one real request offering a single tool; the model is enabled for coding only if it calls it correctly. */
    suspend fun verifyToolCalling(id: String, model: String): Boolean {
        val probe = ToolSpec("report_ready", "Report that you are ready.",
            """{"type":"object","properties":{"ready":{"type":"boolean"}},"required":["ready"]}""")
        // A provider that refuses tools for this model (HTTP 4xx) counts as a failed check; network failures propagate.
        val events = try {
            turn(id, model, AgentRequest("You are testing tool support. Respond only by calling the tool.",
                listOf(AgentItem.User("Call report_ready with ready set to true.")), listOf(probe)), requireVerified = false).toList()
        } catch (refused: ProviderFailure.Rejected) { emptyList() }
        val ok = events.any { it is ProviderEvent.Item && (it.item as? AgentItem.ToolCall)?.let { c ->
            c.name == "report_ready" && runCatching { JSONObject(c.arguments).getBoolean("ready") }.getOrDefault(false) } == true }
        val now = System.currentTimeMillis()
        prefs.edit().apply { if (ok) { putLong("verified.$id/$model", now); remove("failed.$id/$model") } else { putLong("failed.$id/$model", now); remove("verified.$id/$model") } }.apply()
        ProviderStores.usage.recordModel(ModelVerification(id, model, ok, now))
        return ok
    }

    // ---- agent turns ----
    override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> {
        val id = selected ?: return flow { throw ProviderFailure.AuthInvalid("Choose a provider in Accounts → Free & trial providers") }
        val model = model(id) ?: return flow { throw ProviderFailure.Rejected("Choose a model for this provider in Accounts") }
        return turn(id, model, request, requireVerified = request.tools.isNotEmpty())
    }

    private fun turn(id: String, model: String, request: AgentRequest, requireVerified: Boolean): Flow<ProviderEvent> {
        val entry = entry(id) ?: return flow { throw ProviderFailure.AuthInvalid("This provider is no longer configured") }
        return flow {
            lock.withLock {
                if (requireVerified && verifiedAt(id, model) == null)
                    throw ProviderFailure.Rejected("$model has not passed the tool-calling check. Verify it in Accounts before using it for coding.")
                val key = keyFor(entry) ?: if (entry.quirks.keyOptional) null else throw ProviderFailure.AuthInvalid("Add a ${entry.descriptor.displayName} key in Accounts")
                emitAll(engine.turn(entry, baseUrl(entry), key, model, request, freeOnly, planConfirmed(id)))
            }
        }.asProviderFailures(entry.descriptor.host, entry.descriptor.displayName, diagnostics).flowOn(Dispatchers.IO)
    }

    override fun cancel() = engine.cancel()

    companion object {
        // Holds only the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CompatProviders? = null
        /** One instance per process (settings, catalogs and the in-flight call are shared with Accounts). */
        fun shared(context: Context): CompatProviders =
            instance ?: synchronized(this) { instance ?: CompatProviders(context).also { instance = it } }
    }

    fun accountState(): AccountState {
        val id = selected ?: return AccountState(ProviderId.OPENAI_COMPAT, AccountStatus.DISCONNECTED, "No free/trial provider chosen")
        val entry = entry(id) ?: return AccountState(ProviderId.OPENAI_COMPAT, AccountStatus.DISCONNECTED, "Provider no longer configured")
        val model = model(id)
        val ready = (hasKey(id) || entry.quirks.keyOptional) && model != null && verifiedAt(id, model) != null
        return AccountState(ProviderId.OPENAI_COMPAT, if (ready) AccountStatus.CONNECTED else AccountStatus.DISCONNECTED,
            "${entry.descriptor.displayName} · ${model ?: "no model"}" + if (ready) "" else " — add a key, choose a model and verify tool calling")
    }
}
