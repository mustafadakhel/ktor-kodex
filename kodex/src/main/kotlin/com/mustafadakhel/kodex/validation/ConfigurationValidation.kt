package com.mustafadakhel.kodex.validation

import kotlin.time.Duration

/**
 * Result of configuration validation.
 */
public sealed interface ValidationResult {
    public data object Valid : ValidationResult
    public data class Invalid(val errors: List<String>) : ValidationResult

    public fun isValid(): Boolean = this is Valid
    public fun errors(): List<String> = if (this is Invalid) errors else emptyList()
}

/**
 * Builder for collecting validation errors.
 */
public class ValidationBuilder {
    private val errors = mutableListOf<String>()

    public fun error(message: String) {
        errors.add(message)
    }

    public fun require(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) {
            errors.add(lazyMessage())
        }
    }

    public fun requirePositive(value: Int, name: String) {
        require(value > 0) { "$name must be positive, got: $value" }
    }

    public fun requireNonNegative(value: Int, name: String) {
        require(value >= 0) { "$name must be non-negative, got: $value" }
    }

    public fun requireNonNegative(value: Long, name: String) {
        require(value >= 0) { "$name must be non-negative, got: $value" }
    }

    public fun requirePositive(duration: Duration, name: String) {
        require(duration.isPositive()) { "$name must be positive, got: $duration" }
    }

    public fun requireInRange(value: Int, min: Int, max: Int, name: String) {
        require(value in min..max) { "$name must be between $min and $max, got: $value" }
    }

    public fun requireNotBlank(value: String?, name: String) {
        require(!value.isNullOrBlank()) { "$name must not be blank" }
    }

    public fun <T> requireNotNull(value: T?, name: String) {
        require(value != null) { "$name must not be null" }
    }

    public fun build(): ValidationResult {
        return if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors.toList())
        }
    }
}

/**
 * Interface for validatable configurations.
 */
public interface ValidatableConfig {
    /**
     * Validates the configuration and returns errors if any.
     */
    public fun validate(): ValidationResult
}

/**
 * DSL for building validation results.
 */
public inline fun validate(block: ValidationBuilder.() -> Unit): ValidationResult {
    val builder = ValidationBuilder()
    builder.block()
    return builder.build()
}
