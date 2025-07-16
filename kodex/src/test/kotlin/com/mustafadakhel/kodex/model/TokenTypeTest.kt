package com.mustafadakhel.kodex.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class TokenTypeTest : FunSpec({
    test("fromClaim converts AccessToken") {
        TokenType.fromClaim(Claim.TokenType.AccessToken) shouldBe TokenType.AccessToken
    }

    test("fromClaim converts RefreshToken") {
        TokenType.fromClaim(Claim.TokenType.RefreshToken) shouldBe TokenType.RefreshToken
    }

    test("fromString converts access") {
        TokenType.fromString("access") shouldBe TokenType.AccessToken
    }

    test("fromString converts refresh") {
        TokenType.fromString("refresh") shouldBe TokenType.RefreshToken
    }

    test("fromString throws on unknown value") {
        shouldThrow<IllegalArgumentException> {
            TokenType.fromString("invalid")
        }
    }
})
