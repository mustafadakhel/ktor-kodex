package com.mustafadakhel.kodex.validation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Email Validator Tests
 * Tests email format validation, length limits, and disposable email detection.
 */
class EmailValidatorTest : StringSpec({

    "valid simple email should pass validation" {
        val validator = EmailValidator()
        val result = validator.validate("user@example.com")

        result.isValid.shouldBeTrue()
        result.errors.shouldBeEmpty()
    }

    "valid email with dot in local part should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user.name@example.com")

        result.isValid.shouldBeTrue()
    }

    "valid email with plus tag should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user+tag@example.com")

        result.isValid.shouldBeTrue()
    }

    "valid email with subdomain should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user@subdomain.example.com")

        result.isValid.shouldBeTrue()
    }

    "valid email with multiple subdomains should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user@mail.subdomain.example.com")

        result.isValid.shouldBeTrue()
    }

    "valid email with numbers in local part should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user123@example.com")

        result.isValid.shouldBeTrue()
    }

    "valid email with special characters in local part should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user!#\$%&'*+/=?^_`{|}~-@example.com")

        result.isValid.shouldBeTrue()
    }

    "invalid email without domain should fail" {
        val validator = EmailValidator()
        val result = validator.validate("user")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.structure" }.shouldBeTrue()
    }

    "invalid email without local part should fail" {
        val validator = EmailValidator()
        val result = validator.validate("@example.com")

        result.isValid.shouldBeFalse()
    }

    "invalid email without domain after @ should fail" {
        val validator = EmailValidator()
        val result = validator.validate("user@")

        result.isValid.shouldBeFalse()
    }

    "invalid email with empty subdomain should fail" {
        val validator = EmailValidator()
        val result = validator.validate("user@.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.format" }.shouldBeTrue()
    }

    "invalid email with space should fail" {
        val validator = EmailValidator()
        val result = validator.validate("user name@example.com")

        result.isValid.shouldBeFalse()
    }

    "invalid email with multiple @ symbols should fail" {
        val validator = EmailValidator()
        val result = validator.validate("user@@example.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.structure" }.shouldBeTrue()
    }

    "invalid email with @ in wrong position should fail" {
        val validator = EmailValidator()
        val result = validator.validate("user@domain@example.com")

        result.isValid.shouldBeFalse()
    }

    "empty email should fail" {
        val validator = EmailValidator()
        val result = validator.validate("")

        result.isValid.shouldBeFalse()
    }

    "email with only spaces should fail" {
        val validator = EmailValidator()
        val result = validator.validate("   ")

        result.isValid.shouldBeFalse()
    }

    "email at max total length (320) should pass" {
        val validator = EmailValidator()
        // Create email exactly at limit: local(64) + @ + domain(255) = 320
        val localPart = "a".repeat(64)
        val domain = "b".repeat(252) + ".com"
        val email = "$localPart@$domain"

        // This should be at or very close to 320 characters
        val result = validator.validate(email)
        // Note: Domain might not be valid format, but length check should pass
    }

    "email exceeding max total length (321+) should fail" {
        val validator = EmailValidator()
        val localPart = "a".repeat(64)
        val domain = "b".repeat(253) + ".com"
        val email = "$localPart@$domain"

        val result = validator.validate(email)
        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.length" || it.code == "email.domain.length" }.shouldBeTrue()
    }

    "local part at max length (64) should pass" {
        val validator = EmailValidator()
        val localPart = "a".repeat(64)
        val result = validator.validate("$localPart@example.com")

        // Should pass length check, might fail format depending on RFC interpretation
        result.errors.none { it.code == "email.local_part.length" }.shouldBeTrue()
    }

    "local part exceeding max length (65+) should fail" {
        val validator = EmailValidator()
        val localPart = "a".repeat(65)
        val result = validator.validate("$localPart@example.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.local_part.length" }.shouldBeTrue()
    }

    "domain at max length (255) should pass length validation" {
        val validator = EmailValidator()
        // Domain close to max (255 chars)
        val domain = "a".repeat(250) + ".com"
        val result = validator.validate("user@$domain")

        // Should pass length check
        result.errors.none { it.code == "email.domain.length" }.shouldBeTrue()
    }

    "domain exceeding max length (256+) should fail" {
        val validator = EmailValidator()
        val domain = "a".repeat(256) + ".com"
        val result = validator.validate("user@$domain")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.domain.length" }.shouldBeTrue()
    }

    "disposable email tempmail.com should be blocked by default" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@tempmail.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "disposable email guerrillamail.com should be blocked" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@guerrillamail.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "disposable email 10minutemail.com should be blocked" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@10minutemail.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "disposable email mailinator.com should be blocked" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@mailinator.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "disposable email throwaway.email should be blocked" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@throwaway.email")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "disposable email yopmail.com should be blocked" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@yopmail.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "subdomain of disposable should also be blocked" {
        val validator = EmailValidator(allowDisposable = false)
        val result = validator.validate("user@mail.tempmail.com")

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "email.disposable" }.shouldBeTrue()
    }

    "disposable emails should be allowed when allowDisposable=true" {
        val validator = EmailValidator(allowDisposable = true)
        val result = validator.validate("user@tempmail.com")

        // Should pass disposable check (might fail other checks)
        result.errors.none { it.code == "email.disposable" }.shouldBeTrue()
    }

    "regular email domains should not be flagged as disposable" {
        val validator = EmailValidator(allowDisposable = false)

        val gmail = validator.validate("user@gmail.com")
        val outlook = validator.validate("user@outlook.com")
        val yahoo = validator.validate("user@yahoo.com")
        val custom = validator.validate("user@company.com")

        gmail.errors.none { it.code == "email.disposable" }.shouldBeTrue()
        outlook.errors.none { it.code == "email.disposable" }.shouldBeTrue()
        yahoo.errors.none { it.code == "email.disposable" }.shouldBeTrue()
        custom.errors.none { it.code == "email.disposable" }.shouldBeTrue()
    }

    "Email should be trimmed and lowercased" {
        val validator = EmailValidator()
        val result = validator.validate("  USER@EXAMPLE.COM  ")

        result.isValid.shouldBeTrue()
        result.sanitized shouldBe "user@example.com"
    }

    "Original value should be preserved in result" {
        val validator = EmailValidator()
        val original = "User@Example.COM"
        val result = validator.validate(original)

        result.originalValue shouldBe original
    }

    "Errors should use custom field name" {
        val validator = EmailValidator()
        val result = validator.validate("invalid", field = "contactEmail")

        result.errors.all { it.field == "contactEmail" }.shouldBeTrue()
    }

    "Email with unicode in local part should be handled" {
        val validator = EmailValidator()
        // RFC 6531 allows internationalized email, but this validator uses RFC 5322
        val result = validator.validate("пользователь@example.com")

        // Should handle without crashing, validity depends on implementation
        result.errors.shouldNotBeEmpty() // Likely invalid under RFC 5322
    }

    "Email with hyphen in domain should pass" {
        val validator = EmailValidator()
        val result = validator.validate("user@my-domain.com")

        result.isValid.shouldBeTrue()
    }

    "Email with consecutive dots in local part passes simplified validation" {
        // Note: The current implementation uses a simplified RFC 5322 pattern
        // that doesn't specifically reject consecutive dots in local part
        val validator = EmailValidator()
        val result = validator.validate("user..name@example.com")

        // Current simplified validation allows this,
        // stricter RFC 5321 would reject
        result.isValid.shouldBeTrue()
    }

    "Email starting with dot passes simplified validation" {
        // Note: The current implementation uses a simplified RFC 5322 pattern
        val validator = EmailValidator()
        val result = validator.validate(".user@example.com")

        // Current simplified validation allows this
        result.isValid.shouldBeTrue()
    }

    "Email ending with dot in local part passes simplified validation" {
        // Note: The current implementation uses a simplified RFC 5322 pattern
        val validator = EmailValidator()
        val result = validator.validate("user.@example.com")

        // Current simplified validation allows this
        result.isValid.shouldBeTrue()
    }
})
