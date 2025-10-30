package com.mustafadakhel.kodex.model

import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class JwtClaimsValidatorTest : FunSpec({
    val issuer = "issuer"
    val audience = "audience"
    val realmValue = "realm"

    // configure claims provider
    val claimsConfig = ClaimsConfig().apply {
        issuer(issuer)
        audience(audience)
    }
    val realm = Realm(realmValue)
    val validator = JwtClaimsValidator(claimProvider = claimsConfig, realm = realm)

    fun futureExpSecs() = CurrentKotlinInstant.plus(60.seconds).epochSeconds
    fun pastExpSecs() = CurrentKotlinInstant.minus(60.seconds).epochSeconds

    fun baseClaims(
        exp: Long = futureExpSecs(),
        iss: String = issuer,
        aud: String = audience,
        roles: List<String> = listOf("admin"),
        type: Claim.TokenType = Claim.TokenType.AccessToken,
        realmClaim: String = realmValue,
    ) = listOf(
        Claim.ExpiresAt(exp),
        Claim.Issuer(iss),
        Claim.Audience(aud),
        Claim.Roles(roles),
        Claim.JwtId(UUID.randomUUID().toString()),
        type,
        Claim.Subject(UUID.randomUUID().toString()),
        Claim.Realm(realmClaim),
        Claim.Custom(emptyMap())
    )

    val expectedRoles = listOf("admin")
    val expectedType = TokenType.AccessToken

    test("returns true when claims are valid") {
        validator.validate(baseClaims(), expectedType, expectedRoles) shouldBe true
    }

    test("returns false for expired token") {
        validator.validate(baseClaims(exp = pastExpSecs()), expectedType, expectedRoles) shouldBe false
    }

    test("returns false when issuer mismatches") {
        validator.validate(baseClaims(iss = "bad"), expectedType, expectedRoles) shouldBe false
    }

    test("returns false when audience mismatches") {
        validator.validate(baseClaims(aud = "other"), expectedType, expectedRoles) shouldBe false
    }

    test("returns false when roles missing") {
        validator.validate(baseClaims(roles = listOf("user")), expectedType, expectedRoles) shouldBe false
    }

    test("returns false when token type mismatches") {
        validator.validate(baseClaims(type = Claim.TokenType.RefreshToken), expectedType, expectedRoles) shouldBe false
    }

    test("returns false when realm mismatches") {
        validator.validate(baseClaims(realmClaim = "other"), expectedType, expectedRoles) shouldBe false
    }
})
