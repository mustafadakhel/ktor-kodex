package com.mustafadakhel.kodex.token.formats

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty

class JwtTokenFormatTest : StringSpec({

    "generates valid JWT tokens" {
        val jwt = TokenGenerator.generate(
            JwtTokenFormat(
                issuer = "test-issuer",
                audience = "test-audience",
                subject = "user-123",
                secret = "test-secret",
                validitySeconds = 3600
            )
        )

        jwt.shouldNotBeEmpty()
        jwt.split(".").size shouldBe 3
    }

    "generates decodable JWT with correct claims" {
        val jwt = TokenGenerator.generate(
            JwtTokenFormat(
                issuer = "my-app",
                audience = "api-users",
                subject = "user-456",
                secret = "test-secret",
                validitySeconds = 7200,
                keyId = "key-1",
                claims = mapOf(
                    "role" to "admin",
                    "permissions" to listOf("read", "write", "delete")
                )
            )
        )

        val decoded = JWT.require(Algorithm.HMAC512("test-secret"))
            .withIssuer("my-app")
            .withAudience("api-users")
            .withSubject("user-456")
            .build()
            .verify(jwt)

        decoded.issuer shouldBe "my-app"
        decoded.audience shouldContain "api-users"
        decoded.subject shouldBe "user-456"
        decoded.keyId shouldBe "key-1"
        decoded.getClaim("role").asString() shouldBe "admin"
        decoded.getClaim("permissions").asList(String::class.java) shouldBe listOf("read", "write", "delete")
        decoded.id shouldNotBe null
    }

    "generates unique tokens with different IDs and timestamps" {
        val format = JwtTokenFormat(
            issuer = "test",
            audience = "test",
            subject = "user",
            secret = "secret",
            validitySeconds = 60
        )

        val jwt1 = TokenGenerator.generate(format)
        val jwt2 = TokenGenerator.generate(format)

        jwt1 shouldNotBe jwt2

        val verifier = JWT.require(Algorithm.HMAC512("secret")).withIssuer("test").build()
        verifier.verify(jwt1)
        verifier.verify(jwt2)
    }

    "supports multiple claim types" {
        val jwt = TokenGenerator.generate(
            JwtTokenFormat(
                issuer = "test",
                audience = "test",
                subject = "user",
                secret = "secret",
                validitySeconds = 60,
                claims = mapOf(
                    "stringClaim" to "value",
                    "intClaim" to 42,
                    "longClaim" to 1234567890L,
                    "boolClaim" to true,
                    "doubleClaim" to 3.14,
                    "listClaim" to listOf("a", "b", "c")
                )
            )
        )

        val decoded = JWT.require(Algorithm.HMAC512("secret")).build().verify(jwt)

        decoded.getClaim("stringClaim").asString() shouldBe "value"
        decoded.getClaim("intClaim").asInt() shouldBe 42
        decoded.getClaim("longClaim").asLong() shouldBe 1234567890L
        decoded.getClaim("boolClaim").asBoolean() shouldBe true
        decoded.getClaim("doubleClaim").asDouble() shouldBe 3.14
        decoded.getClaim("listClaim").asList(String::class.java) shouldBe listOf("a", "b", "c")
    }
})
