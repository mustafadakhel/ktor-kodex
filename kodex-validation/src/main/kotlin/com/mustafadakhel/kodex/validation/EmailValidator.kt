package com.mustafadakhel.kodex.validation

/**
 * Validates email addresses according to RFC 5322 structural requirements.
 * Performs length validation, format checking, and disposable domain detection.
 */
internal class EmailValidator(
    private val allowDisposable: Boolean = false
) {
    private val sanitizer = InputSanitizer()

    private companion object {
        // RFC 5321 max lengths
        const val MAX_EMAIL_LENGTH = 320
        const val MAX_LOCAL_PART_LENGTH = 64
        const val MAX_DOMAIN_LENGTH = 255

        // RFC 5322 simplified pattern - structural validation only
        val EMAIL_PATTERN = Regex(
            "^[a-zA-Z0-9.!#\$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\$"
        )

        // Common disposable email domains - limited set for basic protection
        val DISPOSABLE_DOMAINS = setOf(
            "tempmail.com", "guerrillamail.com", "10minutemail.com", "mailinator.com",
            "throwaway.email", "temp-mail.org", "yopmail.com", "maildrop.cc",
            "trashmail.com", "fakeinbox.com", "sharklasers.com", "guerrillamail.info"
        )
    }

    public fun validate(email: String, field: String = "email"): ValidationResult {
        val sanitized = sanitizer.sanitizeEmail(email)
        val errors = mutableListOf<ValidationError>()

        // Length validation
        if (sanitized.isEmpty() || sanitized.length > MAX_EMAIL_LENGTH) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "email.length",
                    message = "Email must be between 1 and $MAX_EMAIL_LENGTH characters",
                    "actual" to sanitized.length,
                    "max" to MAX_EMAIL_LENGTH
                )
            )
        }

        // Structural validation - must have exactly one @ symbol
        val parts = sanitized.split("@")
        if (parts.size != 2) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "email.structure",
                    message = "Email must contain exactly one @ symbol",
                    "atCount" to (parts.size - 1)
                )
            )
            return ValidationResult.invalid(errors = errors, originalValue = email)
        }

        // Format validation
        if (!EMAIL_PATTERN.matches(sanitized)) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "email.format",
                    message = "Invalid email format"
                )
            )
        }

        // Local part and domain length validation
        if (true) {  // Always execute since we know parts.size == 2
            if (parts[0].length > MAX_LOCAL_PART_LENGTH) {
                errors.add(
                    ValidationError.of(
                        field = field,
                        code = "email.local_part.length",
                        message = "Email local part must not exceed $MAX_LOCAL_PART_LENGTH characters",
                        "actual" to parts[0].length,
                        "max" to MAX_LOCAL_PART_LENGTH
                    )
                )
            }

            if (parts[1].length > MAX_DOMAIN_LENGTH) {
                errors.add(
                    ValidationError.of(
                        field = field,
                        code = "email.domain.length",
                        message = "Email domain must not exceed $MAX_DOMAIN_LENGTH characters",
                        "actual" to parts[1].length,
                        "max" to MAX_DOMAIN_LENGTH
                    )
                )
            }

            // Disposable domain check (exact match or subdomain)
            if (!allowDisposable) {
                val domain = parts[1]
                val isDisposable = DISPOSABLE_DOMAINS.any { disposableDomain ->
                    domain == disposableDomain || domain.endsWith(".$disposableDomain")
                }
                if (isDisposable) {
                    errors.add(
                        ValidationError.of(
                            field = field,
                            code = "email.disposable",
                            message = "Disposable email addresses are not allowed",
                            "domain" to domain
                        )
                    )
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.valid(sanitized = sanitized, originalValue = email)
        } else {
            ValidationResult.invalid(errors = errors, originalValue = email)
        }
    }
}
