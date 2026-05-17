package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.SecretsConfig

/**
 * Verifies JWT signatures using HMAC-512 with kid-based secret lookup.
 *
 * This is the cryptographic gate: every inbound JWT must pass through here
 * before any claims are trusted. Without this, an attacker who knows the
 * issuer/audience/realm can forge tokens that pass claims validation.
 */
internal class JwtSignatureVerifier(
    private val secretsConfig: SecretsConfig,
) {
    /**
     * Verifies the JWT signature and returns the decoded token.
     *
     * @throws KodexThrowable.Authorization.SuspiciousToken if the signature is invalid,
     *         the kid is missing/unrecognized, or the token is malformed.
     */
    fun verify(rawToken: String): DecodedJWT {
        val unverified = try {
            JWT.decode(rawToken)
        } catch (e: Exception) {
            throw KodexThrowable.Authorization.SuspiciousToken("Malformed JWT: ${e.message}")
        }

        val kid = unverified.keyId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("JWT missing kid header")

        val secret = secretsConfig.secretForKid(kid)
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Unrecognized kid: $kid")

        return try {
            JWT.require(Algorithm.HMAC512(secret))
                .build()
                .verify(rawToken)
        } catch (e: JWTVerificationException) {
            throw KodexThrowable.Authorization.SuspiciousToken("JWT signature verification failed: ${e.message}")
        }
    }
}
