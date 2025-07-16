package com.mustafadakhel.kodex.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import com.auth0.jwt.interfaces.Claim as JwtClaim

class ClaimTest : FunSpec({
    test("from returns Roles for roles key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asList(String::class.java) } returns listOf("admin", "user")

        Claim.from(Claim.Roles.Key, jwtClaim) shouldBe Claim.Roles(listOf("admin", "user"))
    }

    test("from returns Realm for realm key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns "example"

        Claim.from(Claim.Realm.Key, jwtClaim) shouldBe Claim.Realm("example")
    }

    test("from returns Issuer for iss key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns "issuer"

        Claim.from(Claim.Issuer.Key, jwtClaim) shouldBe Claim.Issuer("issuer")
    }

    test("from returns Audience for aud key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns "audience"

        Claim.from(Claim.Audience.Key, jwtClaim) shouldBe Claim.Audience("audience")
    }

    test("from returns Custom for custom key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asMap() } returns mapOf("x" to 1)

        Claim.from(Claim.Custom.Key, jwtClaim) shouldBe Claim.Custom(mapOf("x" to 1))
    }

    test("from returns JwtId for jti key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns "jwt-id"

        Claim.from(Claim.JwtId.Key, jwtClaim) shouldBe Claim.JwtId("jwt-id")
    }

    test("from returns Subject for sub key") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns "subject"

        Claim.from(Claim.Subject.Key, jwtClaim) shouldBe Claim.Subject("subject")
    }

    test("from returns TokenType for token_type key with Access") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns Claim.TokenType.AccessToken.value

        Claim.from(Claim.TokenType.Key, jwtClaim) shouldBe Claim.TokenType.AccessToken
    }

    test("from returns TokenType for token_type key with Refresh") {
        val jwtClaim = mockk<JwtClaim>()
        every { jwtClaim.asString() } returns Claim.TokenType.RefreshToken.value

        Claim.from(Claim.TokenType.Key, jwtClaim) shouldBe Claim.TokenType.RefreshToken
    }

    test("from returns unknown for unknown key or null value") {
        val jwtClaim = mockk<JwtClaim>{
            every { asString() } returns null
            every { asList(String::class.java) } returns null
            every { asMap() } returns null
        }
        Claim.from("key", jwtClaim) shouldBe Claim.Unknown("key", null)
        Claim.from(Claim.Realm.Key, null) shouldBe Claim.Realm(null)
        Claim.from(Claim.Roles.Key, null) shouldBe Claim.Roles(listOf())
    }
})
