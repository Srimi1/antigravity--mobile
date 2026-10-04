package dev.srimi.antigravitymobile

import org.junit.Assert.*
import org.junit.Test

class SafeTextTest {
    @Test fun removesCredentialShapesButKeepsTheExplanation() {
        val raw = "Auth failed for https://user:ghp_abcdefghijklmnopqrstuvwxyz0123@github.com/o/r.git " +
            "(Bearer abc.def-ghi) key=AIzaSyA1234567890abcdefghijklmn sk-ant-api03-secretsecret " +
            "sk-proj-abcdefghijklmnop1234 eyJhbGciOiJSUzI1NiJ9.payload.sig https://x.example/cb?code=abc&state=ok"
        val safe = SafeText.redact(raw)
        for (secret in listOf("ghp_", "user:", "abc.def-ghi", "AIzaSy", "sk-ant-api03", "sk-proj-", "eyJhbGci", "code=abc"))
            assertFalse("$secret in $safe", safe.contains(secret))
        assertTrue(safe.startsWith("Auth failed for https://[redacted]@github.com/o/r.git"))
        assertTrue(safe.contains("state=ok"))
    }

    @Test fun friendlyMessagesAreRedacted() {
        val message = friendly(IllegalStateException("https://evil.example: not authorized, token ghp_abcdefghijklmnopqrstuvwxyz0123\nsecond line"))
        assertFalse(message.contains("ghp_"))
        assertFalse(message.contains("second line"))
    }
}
