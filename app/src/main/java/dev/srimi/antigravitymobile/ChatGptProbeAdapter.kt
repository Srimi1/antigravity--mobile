package dev.srimi.antigravitymobile

import android.content.Context
import android.net.Uri
import com.nimbusds.jose.jwk.JWKSet
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
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Experimental documented SIWC probe; live eligibility and inference remain unverified. */
class ChatGptProbeAdapter(private val context: Context) : ProviderAdapter, AgentModel {
    override val providerId = ResponsesWire.PROVIDER
    private val credentials = CredentialStore(context)
    private val prefs = context.getSharedPreferences("host", Context.MODE_PRIVATE)
    private val hostId = prefs.getString("hostId", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
        check(prefs.edit().putString("hostId", it).commit())
    }
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val sessionLock = Mutex()
    @Volatile private var call: Call? = null
    @Volatile private var listener: ServerSocket? = null
    override val capabilities = ProviderCapabilities(CheckStatus.UNVERIFIED, true, true,
        "Documented Sign in with ChatGPT route. Entitlement requires a completed live request.")

    private fun random(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun form(fields: Map<String, String>) = FormBody.Builder().apply {
        fields.forEach { (key, value) -> add(key, value) }
    }.build()
    private fun json(request: Request): JSONObject {
        val pending = http.newCall(request)
        call = pending
        try {
            return pending.execute().use { response ->
                if (!response.isSuccessful) throw ProviderFailure(ResponsesWire.describe(response.code,
                    response.header("Content-Type").orEmpty(), response.peekBody(65_536).string()).replace("OpenAI replied", "OpenAI account request replied"))
                JSONObject(response.body?.string() ?: error("Provider returned an empty response"))
            }
        } finally { call = null }
    }
    private fun token(fields: Map<String, String>) = json(Request.Builder()
        .url("https://auth.openai.com/api/accounts/oauth/token").post(form(fields)).build())

    override suspend fun authenticate(openBrowser: (String) -> Unit) = sessionLock.withLock {
        withContext(Dispatchers.IO) {
            val old = credentials.read()
            val clientId = old?.getString("client_id") ?: "dynamic_agent_client"
            val state = random()
            val nonce = random()
            val verifier = random()
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            listener = server
            val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
            try {
                val builder = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                mapOf("client_id" to clientId, "ext_agent_host_id" to hostId,
                    "response_type" to "code", "redirect_uri" to redirect,
                    "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                    "resource" to "https://api.openai.com/v1", "state" to state, "nonce" to nonce,
                    "code_challenge_method" to "S256", "code_challenge" to challenge
                ).forEach { (k, v) -> builder.appendQueryParameter(k, v) }
                if (old == null) builder.appendQueryParameter("agent_name_hint", "Antigravity Mobile")
                else old.optString("id_token").takeIf { it.isNotEmpty() }?.let {
                    builder.appendQueryParameter("id_token_hint", it)
                }
                SignInKeepAlive.start(context.applicationContext)
                withContext(Dispatchers.Main) { openBrowser(builder.build().toString()) }
                val deadline = System.currentTimeMillis() + 180_000
                var callback: Uri? = null
                while (callback == null && System.currentTimeMillis() < deadline) {
                    server.soTimeout = (deadline - System.currentTimeMillis()).coerceIn(1, 180_000).toInt()
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val request = socket.getInputStream().bufferedReader().readLine()?.take(8192).orEmpty()
                        val target = request.split(' ').getOrNull(1).orEmpty()
                        val uri = Uri.parse("http://127.0.0.1$target")
                        val valid = request.startsWith("GET ") && uri.path == "/auth/callback" &&
                            MessageDigest.isEqual(uri.getQueryParameter("state").orEmpty().toByteArray(), state.toByteArray())
                        val page = if (valid) "<!doctype html><meta name=viewport content='width=device-width'>" +
                            "<body style='font-family:sans-serif;padding:24px'><h2>Signed in</h2><p>Antigravity Mobile is finishing sign-in." +
                            "</p><p><a style='font-size:20px' href='dev.srimi.antigravitymobile://signed-in'>Return to Antigravity Mobile</a></p>"
                            else "Invalid callback."
                        socket.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\n" +
                            "Content-Type: ${if (valid) "text/html" else "text/plain"}; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n" +
                            "Content-Length: ${page.toByteArray().size}\r\n\r\n$page").toByteArray())
                        if (valid) callback = uri
                    }
                }
                val result = callback ?: error("Sign-in timed out; start a new attempt")
                check(result.getQueryParameter("error") == null) { "Sign-in was declined or unavailable" }
                val issued = result.getQueryParameter("client_id") ?: old?.getString("client_id")
                    ?: error("Registration did not issue a client ID")
                check(issued != "dynamic_agent_client" && (old == null || issued == clientId)) { "Registration mismatch" }
                val code = result.getQueryParameter("code") ?: error("Authorization code was not returned")
                val tokens = token(mapOf("grant_type" to "authorization_code", "client_id" to issued,
                    "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect,
                    "resource" to "https://api.openai.com/v1"))
                val subject = validateIdToken(tokens.getString("id_token"), issued, nonce)
                check(old == null || subject == old.getString("subject")) { "Signed-in account did not match saved registration" }
                saveTokens(tokens, issued, subject, null)
            } finally { server.close(); listener = null; SignInKeepAlive.stop(context.applicationContext) }
        }
    }

    private fun validateIdToken(raw: String, clientId: String, nonce: String?): String {
        val jwks = json(Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build())
        return OidcVerifier.verify(raw, clientId, nonce, JWKSet.parse(jwks.toString()))
    }
    private fun saveTokens(tokens: JSONObject, clientId: String, subject: String, previous: JSONObject?) {
        val scope = tokens.optString("scope", previous?.optString("scope").orEmpty())
        check("chatgpt.tokens.use.direct" in scope.split(' ')) { "ChatGPT plan usage was not granted" }
        check(tokens.optString("token_type").equals("Bearer", true)) { "Unexpected token type" }
        val expires = tokens.getLong("expires_in")
        check(expires > 0 && tokens.getString("access_token").isNotBlank()) { "Invalid token response" }
        val saved = JSONObject().put("client_id", clientId).put("subject", subject).put("scope", scope)
            .put("access_token", tokens.getString("access_token"))
            .put("refresh_token", tokens.optString("refresh_token", previous?.optString("refresh_token").orEmpty()))
            .put("id_token", tokens.optString("id_token", previous?.optString("id_token").orEmpty()))
            .put("expires_at", System.currentTimeMillis() + expires * 1000)
        credentials.save(saved)
    }
    private fun refresh() {
        val old = credentials.read() ?: error("Connect your ChatGPT account first")
        check(old.optString("refresh_token").isNotEmpty()) { "Reconnect your ChatGPT account" }
        val next = token(mapOf("grant_type" to "refresh_token", "client_id" to old.getString("client_id"),
            "refresh_token" to old.getString("refresh_token"), "resource" to "https://api.openai.com/v1"))
        if (next.has("id_token")) check(validateIdToken(next.getString("id_token"), old.getString("client_id"), null) == old.getString("subject")) {
            "Account identity changed during renewal"
        }
        saveTokens(next, old.getString("client_id"), old.getString("subject"), old)
    }
    override suspend fun renewCredentials() = sessionLock.withLock { withContext(Dispatchers.IO) { refresh() } }

    private fun accessToken(): String {
        var saved = credentials.read() ?: error("Connect your ChatGPT account first")
        if (saved.optString("access_token").isEmpty()) error("Reconnect your ChatGPT account")
        if (saved.getLong("expires_at") < System.currentTimeMillis() + 60_000) { refresh(); saved = credentials.read()!! }
        return saved.getString("access_token")
    }
    private fun catalog(access: String): List<String> {
        val catalog = json(Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $access").build())
        val models = catalog.getJSONArray("models")
        return (0 until models.length()).map { models.getJSONObject(it) }
            .filter { it.optString("visibility") == "list" }.map { it.getString("slug") }
    }
    /** Models the account's catalog lists for this client. Requires a connected account. */
    suspend fun listModels(): List<String> = sessionLock.withLock {
        withContext(Dispatchers.IO) { catalog(accessToken()).also { cachedModels = System.currentTimeMillis() to it } }
    }
    var preferredModel: String?
        get() = prefs.getString("model", null)
        set(value) { prefs.edit().apply { if (value == null) remove("model") else putString("model", value) }.apply() }

    override fun streamTurn(prompt: String): Flow<ProviderEvent> =
        streamAgentTurn(AgentRequest("", listOf(AgentItem.User(prompt)), emptyList()))

    override fun streamAgentTurn(request: AgentRequest): Flow<ProviderEvent> = flow {
        sessionLock.withLock {
            val access = accessToken()
            val listed = cachedModels?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.second
                ?: catalog(access).also { cachedModels = System.currentTimeMillis() to it }
            val model = preferredModel?.takeIf { it in listed } ?: listed.firstOrNull()
                ?: error("No eligible model was returned for this account")
            var reasoning = model !in plainModels
            var response = post(access, ResponsesWire.body(model, request, reasoning))
            if (response.code == 400 && reasoning) {
                // Some models reject encrypted reasoning state; retrying the rejected request has no side effects.
                response.close()
                plainModels += model
                reasoning = false
                response = post(access, ResponsesWire.body(model, request, reasoning))
            }
            try {
                response.use {
                    val contentType = it.header("Content-Type").orEmpty()
                    if (!it.isSuccessful) throw ProviderFailure(ResponsesWire.describe(it.code, contentType, boundedBody(it)))
                    if (!contentType.lowercase().startsWith("text/event-stream")) {
                        ResponsesWire.unlabelled(it.code, contentType, boundedBody(it)).forEach { event -> emit(event) }
                        prefs.edit().putLong("verifiedAt", System.currentTimeMillis()).apply()
                        return@use
                    }
                    val reader = it.body?.charStream()?.buffered() ?: error("Empty inference stream")
                    val parser = ResponsesStreamParser()
                    var line = reader.readLine()
                    while (line != null) {
                        parser.line(line).forEach { event -> emit(event) }
                        if (parser.completed) break
                        line = reader.readLine()
                    }
                    if (!parser.completed) parser.finish().forEach { event -> emit(event) }
                    check(parser.completed) { "Stream ended without response.completed" }
                    prefs.edit().putLong("verifiedAt", System.currentTimeMillis()).apply()
                }
            } finally { call = null }
        }
    }.flowOn(Dispatchers.IO)

    /** Reads at most 1 MB of a reply that is not an event stream, for parsing or a short explanation. */
    private fun boundedBody(response: Response): String = response.body?.source()?.let { source ->
        source.request(1_048_576); val buffer = source.buffer
        buffer.readString(minOf(buffer.size, 1_048_576L), Charsets.UTF_8)
    }.orEmpty()
    @Volatile private var cachedModels: Pair<Long, List<String>>? = null
    private val plainModels = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private fun post(access: String, body: JSONObject): Response {
        val pending = http.newCall(Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $access")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        call = pending
        return pending.execute()
    }

    /** Local account state only; VERIFIED means a streamed response completed on this install. */
    fun accountState(now: Long = System.currentTimeMillis()): AccountState {
        val saved = try { credentials.read() } catch (_: Exception) {
            return AccountState(ProviderId.CHATGPT, AccountStatus.DISCONNECTED, "Stored credentials could not be read. Reconnect.")
        }
        if (saved == null || saved.optString("access_token").isEmpty())
            return AccountState(ProviderId.CHATGPT, AccountStatus.DISCONNECTED, "Not connected")
        val expired = saved.optLong("expires_at") < now
        val verifiedAt = prefs.getLong("verifiedAt", 0)
        return when {
            expired && saved.optString("refresh_token").isEmpty() ->
                AccountState(ProviderId.CHATGPT, AccountStatus.EXPIRED, "Session expired. Reconnect.")
            expired -> AccountState(ProviderId.CHATGPT, AccountStatus.EXPIRED, "Access expired; it renews automatically on the next request.")
            verifiedAt > 0 -> AccountState(ProviderId.CHATGPT, AccountStatus.VERIFIED,
                "A subscription response completed on this phone at ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(verifiedAt))}.")
            else -> AccountState(ProviderId.CHATGPT, AccountStatus.CONNECTED, "Signed in. Plan access is unverified until a response completes.")
        }
    }

    override fun cancel() { call?.cancel(); listener?.close() }
    override suspend fun disconnect(): Boolean = sessionLock.withLock {
        withContext(Dispatchers.IO) {
            val saved = credentials.read() ?: return@withContext true
            var revoked = saved.optString("refresh_token").isEmpty()
            if (!revoked) {
                try {
                    val config = json(Request.Builder().url("https://auth.openai.com/.well-known/openid-configuration").build())
                    val endpoint = config.getString("revocation_endpoint")
                    check(endpoint.startsWith("https://auth.openai.com/")) { "Unexpected revocation endpoint" }
                    val pending = http.newCall(Request.Builder().url(endpoint).post(form(mapOf(
                        "token" to saved.getString("refresh_token"), "token_type_hint" to "refresh_token",
                        "client_id" to saved.getString("client_id")))).build())
                    call = pending
                    pending.execute().use { revoked = it.code == 200 }
                } catch (_: Exception) { revoked = false } finally { call = null }
            }
            // Preserve account/client mapping and host; erase every reusable credential.
            listOf("access_token", "refresh_token", "id_token").forEach { saved.remove(it) }
            credentials.save(saved)
            prefs.edit().remove("verifiedAt").apply()
            cachedModels = null
            revoked
        }
    }
}
