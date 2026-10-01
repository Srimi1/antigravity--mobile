package dev.srimi.antigravitymobile.providers

import dev.srimi.antigravitymobile.ClaudeWire
import dev.srimi.antigravitymobile.GeminiWire
import dev.srimi.antigravitymobile.ResponsesWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ProviderFailureTest {
    @Test fun httpStatusesMapToTypedFailures() {
        assertTrue(FailureClassifier.http(401, "x") is ProviderFailure.AuthInvalid)
        assertTrue(FailureClassifier.http(403, "x") is ProviderFailure.AuthInvalid)
        assertTrue(FailureClassifier.http(402, "x") is ProviderFailure.QuotaExhausted)
        val limited = FailureClassifier.http(429, "x", "rate_limit_exceeded", 30)
        assertTrue(limited is ProviderFailure.RateLimited)
        assertEquals(30L, (limited as ProviderFailure.RateLimited).retryAfterSeconds)
        assertTrue(FailureClassifier.http(429, "x", "insufficient_quota") is ProviderFailure.QuotaExhausted)
        assertTrue(FailureClassifier.http(429, "x", dailyQuota = true) is ProviderFailure.QuotaExhausted)
        assertTrue(FailureClassifier.http(403, "x", "trial_expired") is ProviderFailure.TrialExpired)
        assertTrue(FailureClassifier.http(504, "x") is ProviderFailure.Timeout)
        assertTrue(FailureClassifier.http(400, "bad schema", "invalid_request_error") is ProviderFailure.Rejected)
        assertTrue(FailureClassifier.http(503, "x") is ProviderFailure.Unknown)
        assertTrue(FailureClassifier.http(400, "Refresh failed", "invalid_grant") is ProviderFailure.AuthInvalid)
    }

    @Test fun ioExceptionsMapThroughCauseChains() {
        assertTrue(FailureClassifier.io(UnknownHostException("api.openai.com"), "OpenAI", false) is ProviderFailure.Dns)
        assertTrue(FailureClassifier.io(RuntimeException("sdk", UnknownHostException("h")), "X", false) is ProviderFailure.Dns)
        assertTrue(FailureClassifier.io(SocketTimeoutException("timeout"), "X", false) is ProviderFailure.Timeout)
        assertTrue(FailureClassifier.io(SocketTimeoutException("timeout"), "X", true) is ProviderFailure.StreamInterrupted)
        assertTrue(FailureClassifier.io(ConnectException("refused"), "X", false) is ProviderFailure.NoNetwork)
        assertTrue(FailureClassifier.io(EOFException(), "X", true) is ProviderFailure.StreamInterrupted)
        assertTrue(FailureClassifier.io(IOException("unexpected end of stream on https://h/..."), "X", false) is ProviderFailure.StreamInterrupted)
        assertTrue(FailureClassifier.io(SSLHandshakeException("bad cert"), "X", false) is ProviderFailure.Unknown)
        // Deliberate cancellation (Stop) is never turned into a provider failure.
        assertNull(FailureClassifier.io(IOException("Canceled"), "X", true))
        assertNull(FailureClassifier.io(CancellationException("stop"), "X", true))
        assertNull(FailureClassifier.io(IllegalArgumentException("not io"), "X", false))
    }

    @Test fun retryAfterParsesSecondsAndDates() {
        assertEquals(12L, FailureClassifier.retryAfter("12"))
        assertEquals(60L, FailureClassifier.retryAfter("Wed, 21 Oct 2015 07:29:00 GMT", 1445412480000))
        assertNull(FailureClassifier.retryAfter(null))
        assertNull(FailureClassifier.retryAfter("soon"))
    }

    @Test fun flowFailuresAreTypedAndDiagnosed() = runBlocking {
        val diagnostics = object : NetworkDiagnostics {
            var calls = 0
            override suspend fun diagnose(host: String, port: Int): DiagnosticReport { calls++; return report(host) }
        }
        val failed = runCatching {
            flow<Int> { emit(1); throw SocketTimeoutException("timeout") }.asProviderFailures("h.example", "H", diagnostics).toList()
        }.exceptionOrNull()
        assertTrue(failed is ProviderFailure.StreamInterrupted)
        assertEquals(ConnectionStage.Stream, (failed as ProviderFailure).diagnostic!!.failedStage)
        // Account failures are not network problems: no probe.
        val auth = runCatching { flow<Int> { throw ProviderFailure.AuthInvalid("bad key") }.asProviderFailures("h", "H", diagnostics).toList() }.exceptionOrNull()
        assertTrue(auth is ProviderFailure.AuthInvalid)
        assertEquals(1, diagnostics.calls)
        // Non-I/O programming errors pass through unchanged.
        val other = runCatching { flow<Int> { error("bug") }.asProviderFailures("h", "H", diagnostics).toList() }.exceptionOrNull()
        assertTrue(other is IllegalStateException)
    }

    @Test fun withDiagnosticKeepsTypeAndFields() {
        val original = ProviderFailure.RateLimited("slow down", 7)
        val copy = original.withDiagnostic(report("h"))
        assertTrue(copy is ProviderFailure.RateLimited)
        assertEquals(7L, (copy as ProviderFailure.RateLimited).retryAfterSeconds)
        assertEquals("slow down", copy.message)
        assertNotNull(copy.diagnostic)
        assertFalse(ProviderFailure.QuotaExhausted("x").retryable)
        assertTrue(ProviderFailure.Dns("x").retryable)
    }

    @Test fun geminiDistinguishesPerMinuteAndPerDayQuota() {
        val perMinute = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"You exceeded your current quota","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""
        val minute = GeminiWire.failure(429, perMinute)
        assertTrue(minute is ProviderFailure.RateLimited)
        assertEquals(37L, (minute as ProviderFailure.RateLimited).retryAfterSeconds)
        val perDay = perMinute.replace("PerMinute", "PerDay").replace(",\n            {\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"37s\"}", "")
        assertTrue(GeminiWire.failure(429, perDay) is ProviderFailure.QuotaExhausted)
        val badKey = """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"API key not valid. AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMY","details":[{"reason":"API_KEY_INVALID"}]}}"""
        val auth = GeminiWire.failure(400, badKey)
        assertTrue(auth is ProviderFailure.AuthInvalid)
        assertFalse(auth.message!!.contains("AIza"))
    }

    @Test fun openAiAndAnthropicRepliesAreTyped() {
        val quota = ResponsesWire.failure(429, "application/json", """{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}""")
        assertTrue(quota is ProviderFailure.QuotaExhausted)
        val limited = ResponsesWire.failure(429, "application/json", """{"error":{"code":"rate_limit_exceeded","message":"Bearer sk-abc123 slow"}}""", "3")
        assertTrue(limited is ProviderFailure.RateLimited)
        assertFalse(limited.message!!.contains("sk-abc123"))
        val credit = ClaudeWire.failure(400, """400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""")
        assertTrue(credit is ProviderFailure.QuotaExhausted)
        assertTrue(ClaudeWire.failure(401, """401: {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""") is ProviderFailure.AuthInvalid)
        assertTrue(ClaudeWire.failure(429, """429: {"type":"error","error":{"type":"rate_limit_error","message":"rate"}}""", "20") is ProviderFailure.RateLimited)
        assertTrue(ResponsesWire.streamFailure("response.failed", "insufficient_quota", "m") is ProviderFailure.QuotaExhausted)
    }

    @Test fun usageStoreTotalsKeepUnknownTokensUnknown() = runBlocking {
        val store = InMemoryProviderUsageStore()
        store.record(UsageRecord("groq", "m1", "t1", null, null, 1, 100))
        store.record(UsageRecord("groq", "m1", null, null, null, 2, 200))
        assertEquals(UsageTotals(3, null, null, 0), store.totals("groq", null, 0))
        store.record(UsageRecord("groq", "m2", null, 10, 5, 1, 300))
        assertEquals(UsageTotals(1, 10, 5, 250), store.totals("groq", null, 250))
        assertEquals(3L, store.totals("groq", "m1", 0).requests)
        store.recordModel(ModelVerification("groq", "m1", true, 1))
        store.recordModel(ModelVerification("groq", "m1", false, 2))
        assertEquals(listOf(ModelVerification("groq", "m1", false, 2)), store.models("groq"))
    }

    private fun report(host: String) = DiagnosticReport(host, 0, "Wi-Fi", true, true, false, false, PrivateDnsMode.Off, null,
        listOf("192.0.2.1"), null, "ok", null, 0)
}
