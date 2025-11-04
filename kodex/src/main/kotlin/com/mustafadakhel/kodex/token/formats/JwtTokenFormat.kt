package com.mustafadakhel.kodex.token.formats

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.tokens.token.TokenFormat
import com.mustafadakhel.kodex.tokens.token.UUIDv7Format
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaInstant
import java.security.SecureRandom
import java.util.Date

/**
 * Token format that generates signed JWT tokens using HMAC512.
 * Uses UUIDv7 for time-ordered JWT IDs.
 */
public data class JwtTokenFormat(
    val issuer: String,
    val audience: String,
    val subject: String,
    val secret: String,
    val validitySeconds: Long,
    val keyId: String? = null,
    val claims: Map<String, Any> = emptyMap()
) : TokenFormat<String> {

    override fun generate(random: SecureRandom): String {
        val now = CurrentKotlinInstant
        val expiresAt = now.plus(validitySeconds, DateTimeUnit.SECOND)

        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(subject)
            .withJWTId(UUIDv7Format.generate(random).toString())
            .withIssuedAt(Date.from(now.toJavaInstant()))
            .withExpiresAt(Date.from(expiresAt.toJavaInstant()))
            .apply { keyId?.let { withKeyId(it) } }
            .apply { addClaims(this, claims) }
            .sign(Algorithm.HMAC512(secret))
    }

    private fun addClaims(builder: JWTCreator.Builder, claims: Map<String, Any>) {
        claims.forEach { (key, value) ->
            when (value) {
                is String -> builder.withClaim(key, value)
                is Int -> builder.withClaim(key, value)
                is Long -> builder.withClaim(key, value)
                is Boolean -> builder.withClaim(key, value)
                is Double -> builder.withClaim(key, value)
                is List<*> -> addListClaim(builder, key, value)
            }
        }
    }

    private fun addListClaim(builder: JWTCreator.Builder, key: String, list: List<*>) {
        if (list.isEmpty()) return
        @Suppress("UNCHECKED_CAST")
        when (list.first()) {
            is String -> builder.withArrayClaim(key, (list as List<String>).toTypedArray())
            is Int -> builder.withArrayClaim(key, (list as List<Int>).toTypedArray())
            is Long -> builder.withArrayClaim(key, (list as List<Long>).toTypedArray())
        }
    }
}
