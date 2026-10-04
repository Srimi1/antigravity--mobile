package dev.srimi.antigravitymobile

/**
 * One redaction pass for any text that reaches the UI, task notes or logs from an exception or a remote reply.
 * Removes bearer tokens, API keys and JWTs in the shapes our providers use, GitHub tokens, URL user info and
 * credential-like query parameters. Callers still prefer their own fixed messages; this is the safety net.
 */
object SafeText {
    private val patterns = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"),
        Regex("sk-ant-[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{16,}"),
        Regex("AIza[0-9A-Za-z_-]{20,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"),
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_.-]+"),
    )
    private val userInfo = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s@]+@")
    private val secretQuery = Regex("(?i)([?&](?:key|api_key|apikey|token|access_token|refresh_token|code|client_secret|password)=)[^&\\s]+")

    fun redact(text: String): String {
        var result = userInfo.replace(text) { "${it.groupValues[1]}[redacted]@" }
        result = secretQuery.replace(result) { "${it.groupValues[1]}[redacted]" }
        patterns.forEach { result = it.replace(result, "[redacted]") }
        return result
    }
}
