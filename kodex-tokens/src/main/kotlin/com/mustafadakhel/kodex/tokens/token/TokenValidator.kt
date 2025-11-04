package com.mustafadakhel.kodex.tokens.token

import kotlinx.datetime.LocalDateTime

/**
 * Token validation utility.
 */
public object TokenValidator {

    public data class ValidationResult(
        val isValid: Boolean,
        val reason: String? = null
    )

    /**
     * Validates a token based on expiration and usage status.
     *
     * @param expiresAt When the token expires
     * @param usedAt When the token was used (null if not used)
     * @param now Current time
     * @return ValidationResult indicating if token is valid and reason if invalid
     */
    public fun validate(
        expiresAt: LocalDateTime,
        usedAt: LocalDateTime?,
        now: LocalDateTime
    ): ValidationResult {
        if (usedAt != null) {
            return ValidationResult(false, "Token already used")
        }
        if (now >= expiresAt) {
            return ValidationResult(false, "Token expired")
        }
        return ValidationResult(true)
    }
}
