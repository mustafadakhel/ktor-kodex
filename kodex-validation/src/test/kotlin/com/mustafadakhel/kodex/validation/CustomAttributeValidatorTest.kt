package com.mustafadakhel.kodex.validation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Custom Attribute Validator Tests
 * Tests attribute count limits, key/value validation, and XSS prevention.
 */
class CustomAttributeValidatorTest : StringSpec({

    "Attributes within limit should pass validation" {
        val validator = CustomAttributeValidator(maxAttributes = 5)
        val attributes = mapOf(
            "key1" to "value1",
            "key2" to "value2",
            "key3" to "value3"
        )
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
        result.sanitizedAttributes?.size shouldBe 3
    }

    "Attributes at exactly the limit should pass" {
        val validator = CustomAttributeValidator(maxAttributes = 3)
        val attributes = mapOf(
            "key1" to "value1",
            "key2" to "value2",
            "key3" to "value3"
        )
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Attributes exceeding limit should fail" {
        val validator = CustomAttributeValidator(maxAttributes = 2)
        val attributes = mapOf(
            "key1" to "value1",
            "key2" to "value2",
            "key3" to "value3"
        )
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attributes.too_many" }.shouldBeTrue()
    }

    "Key within max length should pass" {
        val validator = CustomAttributeValidator(maxKeyLength = 50)
        val attributes = mapOf("valid_key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Key exceeding max length should fail" {
        val validator = CustomAttributeValidator(maxKeyLength = 10)
        val attributes = mapOf("this_key_is_too_long" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.too_long" }.shouldBeTrue()
    }

    "Value within max length should pass" {
        val validator = CustomAttributeValidator(maxValueLength = 100)
        val attributes = mapOf("key" to "short value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Value exceeding max length should fail" {
        val validator = CustomAttributeValidator(maxValueLength = 10)
        val attributes = mapOf("key" to "this value is too long")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.value.too_long" }.shouldBeTrue()
    }

    "Key with alphanumeric characters should pass" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("myKey123" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Key with underscore should pass" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("my_key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Key with hyphen should pass" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("my-key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Key with dot should pass" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("my.key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Key with invalid characters should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("my@key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.invalid_format" }.shouldBeTrue()
    }

    "Key with space should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("my key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.invalid_format" }.shouldBeTrue()
    }

    "Empty key should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.empty" }.shouldBeTrue()
    }

    "Blank key should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("   " to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.empty" }.shouldBeTrue()
    }

    "Reserved key 'id' should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("id" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.reserved" }.shouldBeTrue()
    }

    "Reserved key 'user_id' should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("user_id" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.reserved" }.shouldBeTrue()
    }

    "Reserved key 'created_at' should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("created_at" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.reserved" }.shouldBeTrue()
    }

    "Reserved key 'realm' should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("realm" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.reserved" }.shouldBeTrue()
    }

    "Reserved key check is case-insensitive" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("ID" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.reserved" }.shouldBeTrue()
    }

    "Value with null byte should fail" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("key" to "value\u0000injection")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.value.null_byte" }.shouldBeTrue()
    }

    "Key in allowlist should pass" {
        val validator = CustomAttributeValidator(allowedKeys = setOf("allowed_key"))
        val attributes = mapOf("allowed_key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Key not in allowlist should fail" {
        val validator = CustomAttributeValidator(allowedKeys = setOf("allowed_key"))
        val attributes = mapOf("not_allowed" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.key.not_allowed" }.shouldBeTrue()
    }

    "Null allowlist should allow any valid key" {
        val validator = CustomAttributeValidator(allowedKeys = null)
        val attributes = mapOf("any_key" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Required attribute missing should fail" {
        val rules = mapOf("required_field" to AttributeRule(key = "required_field", required = true))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("other_field" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.required" }.shouldBeTrue()
    }

    "Required attribute present should pass" {
        val rules = mapOf("required_field" to AttributeRule(key = "required_field", required = true))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("required_field" to "value")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Value below min length should fail" {
        val rules = mapOf("field" to AttributeRule(key = "field", minLength = 5))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("field" to "abc")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.value.too_short" }.shouldBeTrue()
    }

    "Value matching pattern should pass" {
        val rules = mapOf("field" to AttributeRule(key = "field", pattern = "^[A-Z]+$"))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("field" to "UPPERCASE")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Value not matching pattern should fail" {
        val rules = mapOf("field" to AttributeRule(key = "field", pattern = "^[A-Z]+$"))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("field" to "lowercase")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.value.pattern_mismatch" }.shouldBeTrue()
    }

    "Value in allowed values should pass" {
        val rules = mapOf("field" to AttributeRule(key = "field", allowedValues = setOf("option1", "option2")))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("field" to "option1")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
    }

    "Value not in allowed values should fail" {
        val rules = mapOf("field" to AttributeRule(key = "field", allowedValues = setOf("option1", "option2")))
        val validator = CustomAttributeValidator(attributeRules = rules)
        val attributes = mapOf("field" to "option3")
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.any { it.code == "attribute.value.not_allowed" }.shouldBeTrue()
    }

    "Valid attributes should be sanitized and returned" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("mykey" to "myvalue")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
        result.sanitizedAttributes?.containsKey("mykey") shouldBe true
    }

    "Values with HTML should be escaped in sanitized output" {
        val validator = CustomAttributeValidator()
        val attributes = mapOf("key" to "<b>bold</b>")
        val result = validator.validate(attributes)

        result.isValid.shouldBeTrue()
        // Sanitized value should have HTML escaped
        result.sanitizedAttributes?.get("key")?.contains("&lt;") shouldBe true
    }

    "validateSingle should validate a single key-value pair" {
        val validator = CustomAttributeValidator()
        val result = validator.validateSingle("valid_key", "valid_value")

        result.isValid.shouldBeTrue()
    }

    "validateSingle should fail for invalid key" {
        val validator = CustomAttributeValidator()
        val result = validator.validateSingle("invalid@key", "value")

        result.isValid.shouldBeFalse()
    }

    "Multiple validation errors should be accumulated" {
        val validator = CustomAttributeValidator(maxKeyLength = 5, maxValueLength = 5)
        val attributes = mapOf(
            "verylongkey" to "value",  // Key too long
            "id" to "test",            // Reserved key
            "key" to "verylongvalue"   // Value too long
        )
        val result = validator.validate(attributes)

        result.isValid.shouldBeFalse()
        result.errors.size shouldBe 3
    }

    "Empty attribute map should pass" {
        val validator = CustomAttributeValidator()
        val result = validator.validate(emptyMap())

        result.isValid.shouldBeTrue()
        result.sanitizedAttributes?.size shouldBe 0
    }
})
