package dev.srimi.antigravitymobile.providers

/*
 * Lane B Day 0 contract (frozen after Day 0; changes go through docs/lanes/REQUESTS.md).
 * Nothing in this file may carry credentials: no API keys, tokens, request bodies or prompts.
 */

/** How a provider authenticates. [OfficialClient] means sign-in happens only inside the provider's own CLI/app. */
enum class AuthType { None, ApiKey, OAuth, OfficialClient }

/**
 * Free: zero-price route with a documented hard stop (requests fail instead of billing).
 * Trial: evaluation credit or promotion that expires or runs out. AccountDependent: limits vary per account and
 * stay unverified until confirmed for this user. Paid: billed per use; only ever used when the user selects it.
 */
enum class AllowanceClass { Free, Trial, AccountDependent, Paid }

/** What the provider requires before requests are accepted. [Unknown] is shown as unknown, never guessed. */
enum class BillingRequirement { None, CardOnFile, PrepaidCredit, PaidPlan, Unknown }

/**
 * A provider route as documented by its official source on [verifiedAt]. Null allowance fields mean the
 * provider does not publish them (or they were not confirmed) and the UI must say "unknown".
 */
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val authType: AuthType,
    val allowanceClass: AllowanceClass,
    /** For example "requests per day" or "credits". Null = unknown. */
    val allowanceUnits: String?,
    /** Documented amount in [allowanceUnits]. Null = unknown. */
    val allowanceAmount: Long?,
    /** For example "daily, 00:00 UTC". Null = unknown or not applicable. */
    val reset: String?,
    /** Epoch millis when a trial or promotion ends. Null = no documented expiry. */
    val expiresAt: Long?,
    val billing: BillingRequirement,
    /** Who may use it, for example "any account" or "new accounts in supported regions". */
    val eligibility: String,
    /** Official page the allowance was read from. */
    val sourceUrl: String,
    /** Epoch millis when [sourceUrl] was last checked. Null = never verified; the route stays unverified. */
    val verifiedAt: Long?,
) {
    val host: String get() = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
}

/** The connection stage that failed, in order. */
enum class ConnectionStage { Network, Dns, Tcp, Tls, Http, Stream }

enum class PrivateDnsMode { Off, Automatic, Strict, Unknown }

/**
 * A credential-free snapshot of why [host] could not be reached. Holds only the hostname, network facts and
 * public addresses; never request URLs with query strings, headers, keys or bodies.
 */
data class DiagnosticReport(
    val host: String,
    val checkedAt: Long,
    /** "Wi-Fi", "Mobile data", "Ethernet" or null when no network is active. */
    val transport: String?,
    val networkAvailable: Boolean,
    /** Android validated the network's internet access. Null = unknown. */
    val validated: Boolean?,
    val captivePortal: Boolean,
    val vpnActive: Boolean,
    val privateDns: PrivateDnsMode,
    /** Strict-mode Private DNS server name, when set. */
    val privateDnsServer: String?,
    /** Addresses [host] resolved to on the active network (empty when DNS failed). */
    val resolvedAddresses: List<String>,
    /** First failing stage, or null when every probe passed. */
    val failedStage: ConnectionStage?,
    /** One plain sentence describing what was observed. */
    val summary: String,
    /** A specific thing the user can do, or null when the cause is inside the app/provider. */
    val recovery: String?,
    /** Network switches/losses seen during the failed request window. */
    val networkChanges: Int,
)

interface NetworkDiagnostics {
    /** Probes [host] (DNS, TCP and TLS on [port]) on the active network. Never sends credentials. */
    suspend fun diagnose(host: String, port: Int = 443): DiagnosticReport
}

/**
 * Every provider failure the agent can see. [retryable] tells the task runner whether "Retry this provider"
 * can succeed without the user changing something. Messages are short and credential-free.
 */
sealed class ProviderFailure(
    message: String,
    val diagnostic: DiagnosticReport? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    abstract val retryable: Boolean

    class Dns(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = true }
    class NoNetwork(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = true }
    class Timeout(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = true }
    class StreamInterrupted(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = true }
    class RateLimited(message: String, val retryAfterSeconds: Long? = null, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = true }
    class QuotaExhausted(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = false }
    class TrialExpired(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = false }
    class PricingChanged(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = false }
    class AuthInvalid(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = false }
    /** The provider understood the request and refused it (safety block, refusal, length limit, invalid request). */
    class Rejected(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = false }
    class Unknown(message: String, diagnostic: DiagnosticReport? = null, cause: Throwable? = null) : ProviderFailure(message, diagnostic, cause) { override val retryable = true }

    /** The same failure with a diagnostic report attached. */
    fun withDiagnostic(report: DiagnosticReport?): ProviderFailure {
        val m = message.orEmpty(); val c = cause
        return when (this) {
            is Dns -> Dns(m, report, c)
            is NoNetwork -> NoNetwork(m, report, c)
            is Timeout -> Timeout(m, report, c)
            is StreamInterrupted -> StreamInterrupted(m, report, c)
            is RateLimited -> RateLimited(m, retryAfterSeconds, report, c)
            is QuotaExhausted -> QuotaExhausted(m, report, c)
            is TrialExpired -> TrialExpired(m, report, c)
            is PricingChanged -> PricingChanged(m, report, c)
            is AuthInvalid -> AuthInvalid(m, report, c)
            is Rejected -> Rejected(m, report, c)
            is Unknown -> Unknown(m, report, c)
        }
    }

    /** Short label for "Paused: <reason>". */
    val reason: String get() = when (this) {
        is Dns -> "the provider's address could not be looked up"
        is NoNetwork -> "no internet connection"
        is Timeout -> "the provider did not answer in time"
        is StreamInterrupted -> "the reply was cut off"
        is RateLimited -> "rate limit reached" + (retryAfterSeconds?.let { " (retry in ${it}s)" } ?: "")
        is QuotaExhausted -> "quota or credit used up"
        is TrialExpired -> "the trial or promotion has ended"
        is PricingChanged -> "the model is no longer free"
        is AuthInvalid -> "the key or sign-in was rejected"
        is Rejected -> "the provider refused the request"
        is Unknown -> "the provider request failed"
    }
}

/** A model's tool-calling probe result. Only verified models are enabled for coding. */
data class ModelVerification(val providerId: String, val modelId: String, val toolCallingVerified: Boolean, val verifiedAt: Long)

/** One provider request as recorded on this phone. Null token counts mean the provider did not report them. */
data class UsageRecord(
    val providerId: String,
    val modelId: String,
    val taskId: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val requests: Int,
    val recordedAt: Long,
)

/** Totals since a point in time. Token totals are null when no record reported tokens. */
data class UsageTotals(val requests: Long, val inputTokens: Long?, val outputTokens: Long?, val since: Long)

/** Lane A provides the Room implementation (tables provider_models and provider_usage, schema v4). */
interface ProviderUsageStore {
    suspend fun recordModel(verification: ModelVerification)
    suspend fun models(providerId: String): List<ModelVerification>
    suspend fun record(usage: UsageRecord)
    suspend fun totals(providerId: String, modelId: String?, since: Long): UsageTotals
}

class InMemoryProviderUsageStore : ProviderUsageStore {
    private val lock = Any()
    private val models = LinkedHashMap<Pair<String, String>, ModelVerification>()
    private val usage = mutableListOf<UsageRecord>()

    override suspend fun recordModel(verification: ModelVerification) = synchronized(lock) {
        models[verification.providerId to verification.modelId] = verification
    }
    override suspend fun models(providerId: String): List<ModelVerification> =
        synchronized(lock) { models.values.filter { it.providerId == providerId } }
    override suspend fun record(usage: UsageRecord) = synchronized(lock) { this.usage += usage }
    override suspend fun totals(providerId: String, modelId: String?, since: Long): UsageTotals = synchronized(lock) {
        val rows = usage.filter { it.providerId == providerId && (modelId == null || it.modelId == modelId) && it.recordedAt >= since }
        fun sum(pick: (UsageRecord) -> Long?) = rows.mapNotNull(pick).takeIf { it.isNotEmpty() }?.sum()
        UsageTotals(rows.sumOf { it.requests.toLong() }, sum { it.inputTokens }, sum { it.outputTokens }, since)
    }
}
