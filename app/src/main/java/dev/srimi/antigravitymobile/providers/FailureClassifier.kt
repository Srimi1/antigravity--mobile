package dev.srimi.antigravitymobile.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Maps HTTP replies and I/O exceptions to [ProviderFailure]. Pure; no Android or credentials. */
object FailureClassifier {
    private val quotaCodes = Regex("(?i)insufficient_quota|quota_exceeded|billing_hard_limit|credit|payment_required|out_of_credits")
    private val trialCodes = Regex("(?i)trial[_ -]?(expired|ended|over)|promotion[_ -]?(expired|ended)")
    private val authCodes = Regex("(?i)invalid_api_key|api_key_invalid|invalid_grant|unauthenticated|authentication_error|permission_denied|invalid_token|expired_token")

    /**
     * [description] is the adapter's already-redacted explanation; [errorCode] the provider's short error code or
     * status; [dailyQuota] true when the provider says a per-day (not per-minute) quota ran out.
     */
    fun http(status: Int, description: String, errorCode: String? = null, retryAfterSeconds: Long? = null, dailyQuota: Boolean = false): ProviderFailure {
        val code = errorCode.orEmpty()
        val text = "$code $description"
        return when {
            trialCodes.containsMatchIn(text) -> ProviderFailure.TrialExpired(description)
            status == 402 -> ProviderFailure.QuotaExhausted(description)
            status == 429 && (dailyQuota || quotaCodes.containsMatchIn(code)) -> ProviderFailure.QuotaExhausted(description)
            status == 429 -> ProviderFailure.RateLimited(description, retryAfterSeconds)
            status == 401 -> ProviderFailure.AuthInvalid(description)
            status == 403 && quotaCodes.containsMatchIn(text) -> ProviderFailure.QuotaExhausted(description)
            status == 403 -> ProviderFailure.AuthInvalid(description)
            status == 400 && quotaCodes.containsMatchIn(text) && Regex("(?i)credit|billing|quota").containsMatchIn(description) -> ProviderFailure.QuotaExhausted(description)
            status == 400 && authCodes.containsMatchIn(text) -> ProviderFailure.AuthInvalid(description)
            status == 408 || status == 504 -> ProviderFailure.Timeout(description)
            status in 400..499 -> ProviderFailure.Rejected(description)
            else -> ProviderFailure.Unknown(description)
        }
    }

    /**
     * Classifies an I/O failure, walking the cause chain (SDKs wrap OkHttp exceptions). Returns null for a
     * deliberate cancellation, which must propagate unchanged. [streaming] is true once reply data arrived.
     */
    fun io(error: Throwable, providerLabel: String, streaming: Boolean): ProviderFailure? {
        if (error is CancellationException || error is ProviderFailure) return null
        val chain = generateSequence(error) { it.cause.takeIf { next -> next !== it } }.take(8).toList()
        if (chain.any { it is IOException && it.message == "Canceled" }) return null
        chain.forEach { cause ->
            when (cause) {
                is UnknownHostException -> return ProviderFailure.Dns("Could not look up $providerLabel's address (DNS)", cause = error)
                is SocketTimeoutException -> return if (streaming) ProviderFailure.StreamInterrupted("$providerLabel stopped sending the reply (timed out)", cause = error)
                    else ProviderFailure.Timeout("$providerLabel did not answer in time", cause = error)
                is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                    return ProviderFailure.NoNetwork("Could not connect to $providerLabel", cause = error)
                is SSLException -> return if (streaming) ProviderFailure.StreamInterrupted("The secure connection to $providerLabel broke during the reply", cause = error)
                    else ProviderFailure.Unknown("A secure connection to $providerLabel could not be made (TLS)", cause = error)
                is EOFException -> return ProviderFailure.StreamInterrupted("The connection to $providerLabel closed early", cause = error)
                is SocketException -> return when {
                    cause.message.orEmpty().contains("unreachable", ignoreCase = true) -> ProviderFailure.NoNetwork("The network is unreachable", cause = error)
                    streaming -> ProviderFailure.StreamInterrupted("The connection to $providerLabel was reset during the reply", cause = error)
                    else -> ProviderFailure.NoNetwork("The connection to $providerLabel failed", cause = error)
                }
                is InterruptedIOException -> if (cause.message.orEmpty().contains("timeout", ignoreCase = true))
                    return if (streaming) ProviderFailure.StreamInterrupted("$providerLabel stopped sending the reply (timed out)", cause = error)
                    else ProviderFailure.Timeout("$providerLabel did not answer in time", cause = error)
            }
        }
        if (chain.none { it is IOException }) return null
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        return when {
            text.contains("unexpected end of stream", true) || text.contains("stream was reset", true) ->
                ProviderFailure.StreamInterrupted("The reply from $providerLabel was cut off", cause = error)
            streaming -> ProviderFailure.StreamInterrupted("The reply from $providerLabel was cut off", cause = error)
            else -> ProviderFailure.Unknown("The request to $providerLabel failed (network error)", cause = error)
        }
    }

    /** Parses a Retry-After header: delta seconds or an HTTP date. */
    fun retryAfter(header: String?, now: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim().orEmpty().ifEmpty { return null }
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.let { ((it - now) / 1000).coerceIn(0, 86_400) }
    }

    /** True for failures caused by the path to the provider, where a network diagnosis helps. */
    fun wantsDiagnosis(failure: ProviderFailure): Boolean = when (failure) {
        is ProviderFailure.Dns, is ProviderFailure.NoNetwork, is ProviderFailure.Timeout, is ProviderFailure.StreamInterrupted -> true
        is ProviderFailure.Unknown -> failure.cause is IOException || failure.message.orEmpty().contains("web page")
        else -> false
    }
}

/**
 * Converts every failure of a provider flow into a [ProviderFailure] (with a diagnosis for network causes).
 * Cancellation and the downstream collector's own exceptions pass through unchanged.
 */
fun <T> Flow<T>.asProviderFailures(host: String, providerLabel: String, diagnostics: NetworkDiagnostics?): Flow<T> = flow {
    var streaming = false
    emitAll(this@asProviderFailures.onEach { streaming = true }.catch { error ->
        val failure = when (error) {
            is ProviderFailure -> error
            else -> FailureClassifier.io(error, providerLabel, streaming) ?: throw error
        }
        throw diagnosed(failure, host, diagnostics)
    })
}

/** Runs a non-streaming provider request (key check, model list) with the same failure mapping. */
suspend fun <T> providerCall(host: String, providerLabel: String, diagnostics: NetworkDiagnostics?, block: suspend () -> T): T = try {
    block()
} catch (error: Throwable) {
    val failure = if (error is ProviderFailure) error else FailureClassifier.io(error, providerLabel, false) ?: throw error
    throw diagnosed(failure, host, diagnostics)
}

private suspend fun diagnosed(failure: ProviderFailure, host: String, diagnostics: NetworkDiagnostics?): ProviderFailure {
    if (failure.diagnostic != null || diagnostics == null || !FailureClassifier.wantsDiagnosis(failure)) return failure
    val report = try { diagnostics.diagnose(host) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    return failure.withDiagnostic(report?.let { r ->
        if (r.failedStage == null && failure is ProviderFailure.StreamInterrupted) r.copy(failedStage = ConnectionStage.Stream) else r
    })
}
