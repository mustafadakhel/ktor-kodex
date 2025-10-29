package com.mustafadakhel.kodex.validation

import java.util.Locale

/**
 * Validates custom attribute key-value pairs with XSS protection.
 * Enforces length limits, key format rules, and value sanitization.
 */
internal class CustomAttributeValidator(
    private val maxKeyLength: Int = 128,
    private val maxValueLength: Int = 4096,
    private val maxAttributes: Int = 50,
    private val allowedKeys: Set<String>? = null,
    private val sanitizer: InputSanitizer = InputSanitizer(maxKeyLength, maxValueLength)
) {
    private companion object {
        // Reserved keys that cannot be used for custom attributes
        val RESERVED_KEYS = setOf("id", "user_id", "created_at", "updated_at", "realm")

        // Allowed characters in attribute keys
        val ALLOWED_KEY_CHARS = setOf('_', '-', '.')

        // Null byte character (potential SQL injection vector)
        const val NULL_BYTE = '\u0000'
    }
    /**
     * Validates a map of custom attributes.
     * Returns ValidationResult with sanitized attributes if valid.
     */
    public fun validate(attributes: Map<String, String>, field: String = "customAttributes"): ValidationResult {
        val errors = mutableListOf<ValidationError>()
        val sanitized = mutableMapOf<String, String>()

        // Check attribute count limit
        if (attributes.size > maxAttributes) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "attributes.too_many",
                    message = "Too many custom attributes (max: $maxAttributes)",
                    "actual" to attributes.size,
                    "max" to maxAttributes
                )
            )
            // Still validate individual attributes, but will return invalid overall
        }

        // Validate each key-value pair
        for ((key, value) in attributes) {
            val keyErrors = validateKey(key, field)
            val valueErrors = validateValue(value, field, key)

            errors.addAll(keyErrors)
            errors.addAll(valueErrors)

            // Sanitize if no errors for this pair
            if (keyErrors.isEmpty() && valueErrors.isEmpty()) {
                try {
                    val (sanitizedKey, sanitizedValue) = sanitizer.sanitizeCustomAttribute(key, value)
                    sanitized[sanitizedKey] = sanitizedValue
                } catch (e: IllegalArgumentException) {
                    errors.add(
                        ValidationError.of(
                            field = "$field.$key",
                            code = "attribute.sanitization_failed",
                            message = e.message ?: "Failed to sanitize attribute",
                            "key" to key
                        )
                    )
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.validAttributes(sanitizedAttributes = sanitized)
        } else {
            ValidationResult.invalid(errors = errors)
        }
    }

    /**
     * Validates a single key-value pair.
     */
    public fun validateSingle(key: String, value: String, field: String = "attribute"): ValidationResult {
        return validate(mapOf(key to value), field)
    }

    private fun validateKey(key: String, field: String): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Empty check
        if (key.isBlank()) {
            errors.add(
                ValidationError.of(
                    field = "$field.key",
                    code = "attribute.key.empty",
                    message = "Attribute key cannot be empty"
                )
            )
            return errors
        }

        // Length check
        if (key.length > maxKeyLength) {
            errors.add(
                ValidationError.of(
                    field = "$field.$key",
                    code = "attribute.key.too_long",
                    message = "Attribute key exceeds maximum length",
                    "key" to key,
                    "actual" to key.length,
                    "max" to maxKeyLength
                )
            )
        }

        // Format check (alphanumeric, underscore, hyphen, dot only)
        if (!key.all { it.isLetterOrDigit() || it in ALLOWED_KEY_CHARS }) {
            errors.add(
                ValidationError.of(
                    field = "$field.$key",
                    code = "attribute.key.invalid_format",
                    message = "Attribute key contains invalid characters (allowed: a-z, A-Z, 0-9, _, -, .)",
                    "key" to key
                )
            )
        }

        // Reserved keys check (use Locale.ROOT for consistent comparison)
        if (key.lowercase(Locale.ROOT) in RESERVED_KEYS) {
            errors.add(
                ValidationError.of(
                    field = "$field.$key",
                    code = "attribute.key.reserved",
                    message = "Attribute key is reserved",
                    "key" to key,
                    "reserved" to RESERVED_KEYS.toList()
                )
            )
        }

        // Allowlist check if configured
        if (allowedKeys != null && key !in allowedKeys) {
            errors.add(
                ValidationError.of(
                    field = "$field.$key",
                    code = "attribute.key.not_allowed",
                    message = "Attribute key is not in allowlist",
                    "key" to key,
                    "allowed" to allowedKeys.toList()
                )
            )
        }

        return errors
    }

    private fun validateValue(value: String, field: String, key: String): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Length check
        if (value.length > maxValueLength) {
            errors.add(
                ValidationError.of(
                    field = "$field.$key",
                    code = "attribute.value.too_long",
                    message = "Attribute value exceeds maximum length",
                    "key" to key,
                    "actual" to value.length,
                    "max" to maxValueLength
                )
            )
        }

        // Check for null bytes (potential SQL injection)
        if (value.contains(NULL_BYTE)) {
            errors.add(
                ValidationError.of(
                    field = "$field.$key",
                    code = "attribute.value.null_byte",
                    message = "Attribute value contains null byte",
                    "key" to key
                )
            )
        }

        return errors
    }
}
