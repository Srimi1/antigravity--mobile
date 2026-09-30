package dev.srimi.antigravitymobile

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT

object OidcVerifier {
    fun verify(raw: String, clientId: String, nonce: String?, keys: JWKSet, now: Long = System.currentTimeMillis()): String {
        val jwt = SignedJWT.parse(raw)
        check(jwt.header.algorithm == JWSAlgorithm.RS256) { "Unsupported ID-token signing algorithm" }
        val key = keys.getKeyByKeyId(jwt.header.keyID) as? RSAKey ?: error("Verification key was not found")
        check(jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) { "Invalid ID-token signature" }
        val claims = jwt.jwtClaimsSet
        check(claims.issuer == "https://auth.openai.com" && clientId in claims.audience &&
            claims.expirationTime != null && claims.expirationTime.time > now &&
            (claims.notBeforeTime == null || claims.notBeforeTime.time <= now)) { "Invalid ID-token claims" }
        if (nonce != null) check(claims.getStringClaim("nonce") == nonce) { "Invalid sign-in nonce" }
        return claims.subject?.takeIf { it.isNotBlank() } ?: error("ID-token subject was missing")
    }
}
