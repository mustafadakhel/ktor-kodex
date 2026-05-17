package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.model.Secrets
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.token.formats.JwtTokenFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.util.SecretsConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk

class JwtSignatureVerifierTest : FunSpec({

    val validSecret = "a]P@5rT#mQ8zL!wK2xN&vY7jD4gB0hF9"
    val wrongSecret = "WRONG_SECRET_THAT_IS_ALSO_32_CHARS!"

    fun secretsConfigWith(vararg secrets: String): SecretsConfig {
        val config = SecretsConfig()
        val scope = mockk<RealmConfigScope>(relaxed = true)
        with(config) { scope.raw(*secrets) }
        return config
    }

    fun issueToken(secret: String, kid: String? = null): String {
        val secretsConfig = secretsConfigWith(secret)
        val (_, resolvedKid) = secretsConfig.randomWithKid()
        return TokenGenerator.generate(
            JwtTokenFormat(
                issuer = "test-issuer",
                audience = "test-audience",
                subject = "user-123",
                secret = secret,
                validitySeconds = 3600,
                keyId = kid ?: resolvedKid,
            )
        )
    }

    test("accepts a correctly signed token") {
        val secretsConfig = secretsConfigWith(validSecret)
        val verifier = JwtSignatureVerifier(secretsConfig)

        val token = issueToken(validSecret)
        val decoded = verifier.verify(token)

        decoded.subject shouldBe "user-123"
        decoded.issuer shouldBe "test-issuer"
    }

    test("rejects a token signed with a different secret") {
        val secretsConfig = secretsConfigWith(validSecret)
        val verifier = JwtSignatureVerifier(secretsConfig)

        val token = issueToken(wrongSecret, kid = "unknown-kid")

        val ex = shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
            verifier.verify(token)
        }
        ex.message shouldContain "Unrecognized kid"
    }

    test("rejects a token with missing kid header") {
        val secretsConfig = secretsConfigWith(validSecret)
        val verifier = JwtSignatureVerifier(secretsConfig)

        val tokenWithoutKid = JWT.create()
            .withIssuer("test-issuer")
            .withSubject("user-123")
            .sign(Algorithm.HMAC512(validSecret))

        val ex = shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
            verifier.verify(tokenWithoutKid)
        }
        ex.message shouldContain "missing kid"
    }

    test("rejects a forged token with correct kid but wrong signature") {
        val secretsConfig = secretsConfigWith(validSecret)
        val verifier = JwtSignatureVerifier(secretsConfig)

        val legitimateToken = issueToken(validSecret)
        val kid = JWT.decode(legitimateToken).keyId

        val forgedToken = JWT.create()
            .withIssuer("test-issuer")
            .withSubject("attacker")
            .withKeyId(kid)
            .sign(Algorithm.HMAC512(wrongSecret))

        val ex = shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
            verifier.verify(forgedToken)
        }
        ex.message shouldContain "signature verification failed"
    }

    test("rejects a malformed token string") {
        val secretsConfig = secretsConfigWith(validSecret)
        val verifier = JwtSignatureVerifier(secretsConfig)

        val ex = shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
            verifier.verify("not.a.jwt")
        }
        ex.message shouldContain "Malformed JWT"
    }

    test("works with multiple secrets and selects correct one by kid") {
        val secret1 = "a]P@5rT#mQ8zL!wK2xN&vY7jD4gB0hF9"
        val secret2 = "b]Q@6sU#nR9aM!xL3yO&wZ8kE5hC1iG0"
        val secretsConfig = secretsConfigWith(secret1, secret2)
        val verifier = JwtSignatureVerifier(secretsConfig)

        val token1 = issueToken(secret1)
        val token2 = issueToken(secret2)

        verifier.verify(token1).subject shouldBe "user-123"
        verifier.verify(token2).subject shouldBe "user-123"
    }
})
