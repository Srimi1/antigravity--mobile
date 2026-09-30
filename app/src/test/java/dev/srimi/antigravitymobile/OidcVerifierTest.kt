package dev.srimi.antigravitymobile

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.*
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class OidcVerifierTest {
    private val key = RSAKeyGenerator(2048).keyID("test").generate()
    private val keys = JWKSet(key.toPublicJWK())
    private fun token(audience: String = "client", issuer: String = "https://auth.openai.com", expiry: Long = 2000, nonce: String = "nonce"): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("subject")
            .expirationTime(Date(expiry)).claim("nonce", nonce).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims)
            .apply { sign(RSASSASigner(key)) }.serialize()
    }
    @Test fun verifiesSignedIdentity() { assertEquals("subject", OidcVerifier.verify(token(), "client", "nonce", keys, 1000)) }
    @Test fun rejectsWrongAudience() { assertThrows(IllegalStateException::class.java) { OidcVerifier.verify(token(audience = "other"), "client", "nonce", keys, 1000) } }
    @Test fun rejectsWrongIssuer() { assertThrows(IllegalStateException::class.java) { OidcVerifier.verify(token(issuer = "https://attacker.invalid"), "client", "nonce", keys, 1000) } }
    @Test fun rejectsExpiredToken() { assertThrows(IllegalStateException::class.java) { OidcVerifier.verify(token(expiry = 500), "client", "nonce", keys, 1000) } }
    @Test fun rejectsNonceMismatch() { assertThrows(IllegalStateException::class.java) { OidcVerifier.verify(token(nonce = "other"), "client", "nonce", keys, 1000) } }
    @Test fun rejectsUntrustedSignature() {
        val other = RSAKeyGenerator(2048).keyID("test").generate()
        assertThrows(IllegalStateException::class.java) { OidcVerifier.verify(token(), "client", "nonce", JWKSet(other.toPublicJWK()), 1000) }
    }
}
