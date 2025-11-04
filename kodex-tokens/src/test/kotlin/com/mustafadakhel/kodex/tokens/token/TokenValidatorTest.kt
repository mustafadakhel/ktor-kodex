package com.mustafadakhel.kodex.tokens.token

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime

class TokenValidatorTest : StringSpec({

    "validate should return valid for unused non-expired token" {
        val expiresAt = LocalDateTime(2025, 12, 31, 23, 59)
        val now = LocalDateTime(2025, 1, 1, 12, 0)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeTrue()
        result.reason shouldBe null
    }

    "validate should return invalid for already used token" {
        val expiresAt = LocalDateTime(2025, 12, 31, 23, 59)
        val usedAt = LocalDateTime(2025, 1, 15, 10, 30)
        val now = LocalDateTime(2025, 1, 20, 12, 0)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = usedAt,
            now = now
        )

        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token already used"
    }

    "validate should return invalid for expired token" {
        val expiresAt = LocalDateTime(2025, 1, 1, 12, 0)
        val now = LocalDateTime(2025, 1, 1, 12, 1)  // 1 minute after expiry

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token expired"
    }

    "validate should return invalid when now equals expiresAt" {
        val expiresAt = LocalDateTime(2025, 1, 1, 12, 0)
        val now = LocalDateTime(2025, 1, 1, 12, 0)  // Exact expiry time

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token expired"
    }

    "validate should prioritize used over expired" {
        // Token is both used AND expired
        val expiresAt = LocalDateTime(2025, 1, 1, 12, 0)
        val usedAt = LocalDateTime(2025, 1, 1, 11, 0)
        val now = LocalDateTime(2025, 1, 1, 13, 0)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = usedAt,
            now = now
        )

        result.isValid.shouldBeFalse()
        // Should report "already used" first
        result.reason shouldBe "Token already used"
    }

    "validate should allow token one second before expiry" {
        val expiresAt = LocalDateTime(2025, 1, 1, 12, 0, 0)
        val now = LocalDateTime(2025, 1, 1, 11, 59, 59)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeTrue()
        result.reason shouldBe null
    }

    "validate should reject token one second after expiry" {
        val expiresAt = LocalDateTime(2025, 1, 1, 12, 0, 0)
        val now = LocalDateTime(2025, 1, 1, 12, 0, 1)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token expired"
    }

    "validate should handle edge case of year boundaries" {
        val expiresAt = LocalDateTime(2025, 12, 31, 23, 59, 59)
        val now = LocalDateTime(2026, 1, 1, 0, 0, 0)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token expired"
    }

    "validate should allow token across year boundaries if not expired" {
        val expiresAt = LocalDateTime(2026, 1, 1, 0, 0, 1)
        val now = LocalDateTime(2025, 12, 31, 23, 59, 59)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeTrue()
        result.reason shouldBe null
    }

    "validate should handle very far future expiry" {
        val expiresAt = LocalDateTime(2099, 12, 31, 23, 59)
        val now = LocalDateTime(2025, 1, 1, 12, 0)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = null,
            now = now
        )

        result.isValid.shouldBeTrue()
        result.reason shouldBe null
    }

    "validate should handle usedAt in the future" {
        // Edge case: usedAt is somehow in the future (clock skew, testing, etc)
        val expiresAt = LocalDateTime(2025, 12, 31, 23, 59)
        val usedAt = LocalDateTime(2025, 6, 1, 12, 0)
        val now = LocalDateTime(2025, 1, 1, 12, 0)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = usedAt,
            now = now
        )

        // Should still be invalid (token marked as used)
        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token already used"
    }

    "validate should handle token used immediately before expiry" {
        val expiresAt = LocalDateTime(2025, 1, 1, 12, 0)
        val usedAt = LocalDateTime(2025, 1, 1, 11, 59, 59)
        val now = LocalDateTime(2025, 1, 1, 12, 1)

        val result = TokenValidator.validate(
            expiresAt = expiresAt,
            usedAt = usedAt,
            now = now
        )

        result.isValid.shouldBeFalse()
        result.reason shouldBe "Token already used"
    }
})
