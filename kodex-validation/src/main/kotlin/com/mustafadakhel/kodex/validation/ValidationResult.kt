package com.mustafadakhel.kodex.validation

public data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val sanitized: String? = null,
    val sanitizedAttributes: Map<String, String>? = null,
    val originalValue: String? = null
) {
    public companion object {
        public fun valid(sanitized: String? = null, originalValue: String? = null): ValidationResult =
            ValidationResult(
                isValid = true,
                errors = emptyList(),
                sanitized = sanitized,
                sanitizedAttributes = null,
                originalValue = originalValue
            )

        public fun validAttributes(sanitizedAttributes: Map<String, String>, originalValue: String? = null): ValidationResult =
            ValidationResult(
                isValid = true,
                errors = emptyList(),
                sanitized = null,
                sanitizedAttributes = sanitizedAttributes,
                originalValue = originalValue
            )

        public fun invalid(vararg errors: ValidationError, originalValue: String? = null): ValidationResult =
            ValidationResult(
                isValid = false,
                errors = errors.toList(),
                sanitized = null,
                originalValue = originalValue
            )

        public fun invalid(errors: List<ValidationError>, originalValue: String? = null): ValidationResult =
            ValidationResult(
                isValid = false,
                errors = errors,
                sanitized = null,
                originalValue = originalValue
            )
    }
}

public data class ValidationError(
    val field: String,
    val code: String,
    val message: String,
    val metadata: Map<String, Any> = emptyMap()
) {
    public companion object {
        public fun of(field: String, code: String, message: String, vararg metadata: Pair<String, Any>): ValidationError =
            ValidationError(
                field = field,
                code = code,
                message = message,
                metadata = metadata.toMap()
            )
    }
}
