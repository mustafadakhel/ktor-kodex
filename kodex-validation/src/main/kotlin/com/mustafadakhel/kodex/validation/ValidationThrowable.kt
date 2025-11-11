package com.mustafadakhel.kodex.validation

import com.mustafadakhel.kodex.throwable.KodexThrowable

public open class ValidationThrowable(
    message: String? = null,
    cause: Throwable? = null
) : KodexThrowable.Validation(message, cause) {

    public data class ValidationFailed(
        override val message: String
    ) : ValidationThrowable(message)

    public data class InvalidEmail(
        val email: String,
        val errors: List<String>
    ) : ValidationThrowable("Invalid email '$email': ${errors.joinToString(", ")}")

    public data class InvalidPhone(
        val phone: String,
        val errors: List<String>
    ) : ValidationThrowable("Invalid phone '$phone': ${errors.joinToString(", ")}")

    public data class WeakPassword(
        val score: Int,
        val feedback: List<String>
    ) : ValidationThrowable("Password too weak (score: $score). ${feedback.joinToString(". ")}")

    public data class InvalidCustomAttribute(
        val key: String,
        val errors: List<String>
    ) : ValidationThrowable("Invalid custom attribute '$key': ${errors.joinToString(", ")}")

    public data class InvalidInput(
        val field: String,
        val errors: List<String>
    ) : ValidationThrowable("Invalid input for field '$field': ${errors.joinToString(", ")}")
}
