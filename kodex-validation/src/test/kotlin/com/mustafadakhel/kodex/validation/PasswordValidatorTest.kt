package com.mustafadakhel.kodex.validation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Password Validator Tests
 * Tests password entropy scoring, common password detection, and pattern detection.
 */
class PasswordValidatorTest : StringSpec({

    "common password 'password' should be VERY_WEAK with score 0" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val strength = validator.analyzeStrength("password")

        strength.score shouldBe 0
        strength.getQualityLevel() shouldBe PasswordQuality.VERY_WEAK
        strength.isAcceptable.shouldBeFalse()
        strength.feedback shouldContain "This password is commonly used and easily guessed"
    }

    "common password '123456' should be VERY_WEAK with score 0" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val strength = validator.analyzeStrength("123456")

        strength.score shouldBe 0
        strength.getQualityLevel() shouldBe PasswordQuality.VERY_WEAK
        strength.isAcceptable.shouldBeFalse()
    }

    "short weak password should be WEAK" {
        val validator = PasswordValidator(minLength = 6, minScore = 2)
        // A password that's not common but short and simple
        val strength = validator.analyzeStrength("xyzabc99")

        // This should score low due to low entropy
        strength.score shouldBeLessThanOrEqual 2
        strength.feedback.shouldNotBeEmpty()
    }

    "moderate password with mixed chars should be MODERATE" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val strength = validator.analyzeStrength("MyP@ssw0rd!")

        strength.score shouldBeGreaterThanOrEqual 2
        strength.getQualityLevel().ordinal shouldBeGreaterThanOrEqual PasswordQuality.MODERATE.ordinal
    }

    "strong password should be STRONG or VERY_STRONG" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val strength = validator.analyzeStrength("Tr0ub4dor&3!Secure")

        strength.score shouldBeGreaterThanOrEqual 3
        strength.entropy shouldBeGreaterThan 60.0
        strength.isAcceptable.shouldBeTrue()
    }

    "passphrase should be VERY_STRONG" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        // Long passphrase with good entropy
        val strength = validator.analyzeStrength("c0Rr3ct-h0rs3-b4tt3ry-st4pl3!!")

        strength.score shouldBeGreaterThanOrEqual 3
        strength.entropy shouldBeGreaterThan 100.0
        strength.getQualityLevel().ordinal shouldBeGreaterThanOrEqual PasswordQuality.STRONG.ordinal
    }

    "entropy calculation increases with character pool diversity" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)

        // Only lowercase - smaller pool
        val lowercaseOnly = validator.analyzeStrength("abcdefgh")

        // Lowercase + uppercase - larger pool
        val mixedCase = validator.analyzeStrength("AbCdEfGh")

        // Lowercase + uppercase + digits - even larger pool
        val withDigits = validator.analyzeStrength("AbCd1234")

        // All character types - largest pool
        val withSpecial = validator.analyzeStrength("AbC1@#\$%")

        // Entropy should increase with pool size
        mixedCase.entropy shouldBeGreaterThan lowercaseOnly.entropy
        withDigits.entropy shouldBeGreaterThan mixedCase.entropy
        withSpecial.entropy shouldBeGreaterThan withDigits.entropy
    }

    "common password 'password' should be detected and score 0" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("password")

        strength.score shouldBe 0
        strength.feedback shouldContain "This password is commonly used and easily guessed"
    }

    "common password '123456' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("123456")

        strength.score shouldBe 0
    }

    "common password 'qwerty' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("qwerty")

        strength.score shouldBe 0
    }

    "common password 'letmein' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("letmein")

        strength.score shouldBe 0
    }

    "common password 'iloveyou' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("iloveyou")

        strength.score shouldBe 0
    }

    "common password 'admin' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("admin")

        strength.score shouldBe 0
    }

    "case-insensitive detection - 'PASSWORD' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("PASSWORD")

        strength.score shouldBe 0
        strength.feedback.any { it.contains("commonly used") }.shouldBeTrue()
    }

    "case-insensitive detection - 'PaSsWoRd' should be detected" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("PaSsWoRd")

        strength.score shouldBe 0
    }

    "unique password should not be flagged as common" {
        val validator = PasswordValidator()
        val strength = validator.analyzeStrength("Un1qu3P@ss!2024")

        (strength.score > 0).shouldBeTrue()
        strength.feedback.none { it.contains("commonly used") }.shouldBeTrue()
    }

    "custom password dictionary can be provided" {
        val customDictionary = setOf("companyname", "internal123")
        val validator = PasswordValidator(commonPasswords = customDictionary)

        val strength1 = validator.analyzeStrength("companyname")
        strength1.score shouldBe 0

        // Default common passwords should not be detected with custom dictionary
        val strength2 = validator.analyzeStrength("password")
        (strength2.score > 0).shouldBeTrue()
    }

    "sequential characters 'abc' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val withSequence = validator.analyzeStrength("MyPabc123!")
        val withoutSequence = validator.analyzeStrength("MyPxyz123!")

        withSequence.feedback.any { it.contains("sequential") }.shouldBeTrue()
    }

    "sequential characters '123' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyPass123word")

        strength.feedback.any { it.contains("sequential") }.shouldBeTrue()
    }

    "reverse sequential 'cba' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyPcba!@#99")

        strength.feedback.any { it.contains("sequential") }.shouldBeTrue()
    }

    "reverse sequential '321' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyPass321word")

        strength.feedback.any { it.contains("sequential") }.shouldBeTrue()
    }

    "longer sequential 'abcdefg' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("abcdefgXYZ!")

        strength.feedback.any { it.contains("sequential") }.shouldBeTrue()
    }

    "keyboard pattern 'qwerty' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyQwerty123!")

        strength.feedback.any { it.contains("keyboard") }.shouldBeTrue()
    }

    "keyboard pattern 'asdf' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyAsdf!@#123")

        strength.feedback.any { it.contains("keyboard") }.shouldBeTrue()
    }

    "keyboard pattern 'zxcv' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyZxcv!@#123")

        strength.feedback.any { it.contains("keyboard") }.shouldBeTrue()
    }

    "repeated characters 'aaa' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyPaaass123!")

        strength.feedback.any { it.contains("repeated") }.shouldBeTrue()
    }

    "repeated characters '111' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyP111ass!@")

        strength.feedback.any { it.contains("repeated") }.shouldBeTrue()
    }

    "four repeated characters 'aaaa' should be penalized" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("MyPaaaass12!")

        strength.feedback.any { it.contains("repeated") }.shouldBeTrue()
    }

    "multiple patterns should accumulate penalties" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)

        // Password with no patterns
        val clean = validator.analyzeStrength("MyP@ssw0rd!X")

        // Password with sequential
        val withSequence = validator.analyzeStrength("Myabc@ssw0rd!")

        // Password with sequential AND repeated
        val withBoth = validator.analyzeStrength("Myabcaaa@ss!")

        // More patterns should result in lower score
        withSequence.score shouldBeLessThanOrEqual clean.score
        withBoth.score shouldBeLessThanOrEqual withSequence.score
    }

    "validate() returns invalid result for password below min length" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val result = validator.validate("Short1!")

        result.isValid.shouldBeFalse()
        result.errors.shouldNotBeEmpty()
        result.errors.any { it.code == "password.too_short" }.shouldBeTrue()
    }

    "validate() returns invalid result for password above max length" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val longPassword = "A".repeat(257) + "1!"
        val result = validator.validate(longPassword)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "password.too_long" }.shouldBeTrue()
    }

    "validate() returns invalid result for weak password" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val result = validator.validate("password")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "password.weak" }.shouldBeTrue()
    }

    "validate() returns valid result for strong password" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val result = validator.validate("MyStr0ng!P@ssword2024")

        result.isValid.shouldBeTrue()
        result.errors.shouldBeEmpty()
    }

    "validate() includes original value in result" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val password = "TestPassword123!"
        val result = validator.validate(password)

        result.originalValue shouldBe password
    }

    "validate() uses custom field name in errors" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val result = validator.validate("weak", field = "newPassword")

        result.errors.all { it.field == "newPassword" }.shouldBeTrue()
    }

    "analyzeStrength provides feedback to add uppercase letters" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("lowercase123!")

        strength.feedback.any { it.contains("uppercase") }.shouldBeTrue()
    }

    "analyzeStrength provides feedback to add lowercase letters" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("UPPERCASE123!")

        strength.feedback.any { it.contains("lowercase") }.shouldBeTrue()
    }

    "analyzeStrength provides feedback to add numbers" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("NoNumbersHere!")

        strength.feedback.any { it.contains("numbers") }.shouldBeTrue()
    }

    "analyzeStrength provides feedback to add special characters" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("NoSpecials123")

        strength.feedback.any { it.contains("special") }.shouldBeTrue()
    }

    "analyzeStrength suggests longer password when below recommended length" {
        val validator = PasswordValidator(minLength = 8, minScore = 0)
        val strength = validator.analyzeStrength("Short1!A")

        strength.feedback.any { it.contains("12 characters") }.shouldBeTrue()
    }

    "empty password should fail validation" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val result = validator.validate("")

        result.isValid.shouldBeFalse()
        result.errors.shouldNotBeEmpty()
    }

    "password with only spaces should fail" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val result = validator.validate("        ")

        result.isValid.shouldBeFalse()
    }

    "unicode characters should be handled correctly" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val strength = validator.analyzeStrength("Пароль123!日本語")

        // Should not throw and should calculate some entropy
        strength.entropy shouldBeGreaterThan 0.0
    }

    "crack time should be positive for non-common passwords" {
        val validator = PasswordValidator(minLength = 8, minScore = 2)
        val strength = validator.analyzeStrength("Un1qu3P@ss!2024")

        strength.crackTime.isPositive() shouldBe true
    }
})
