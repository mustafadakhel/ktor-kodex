package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.FullUser
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Result of an update operation.
 */
public sealed interface UpdateResult {
    /**
     * The update succeeded.
     */
    public data class Success(
        val user: FullUser,
        val changes: ChangeSet
    ) : UpdateResult {
        /**
         * Returns true if any fields were actually changed.
         */
        public fun hasChanges(): Boolean = changes.changedFields.isNotEmpty()
    }

    /**
     * Base interface for all failure results.
     */
    public sealed interface Failure : UpdateResult {
        /**
         * The user was not found.
         */
        public data class NotFound(val userId: UUID) : Failure

        /**
         * Validation failed on one or more fields.
         */
        public data class ValidationFailed(val errors: List<ValidationError>) : Failure

        /**
         * A database constraint was violated (e.g., unique email).
         */
        public data class ConstraintViolation(
            val field: String,
            val reason: String
        ) : Failure

        /**
         * An unknown error occurred.
         */
        public data class Unknown(val message: String, val cause: Throwable? = null) : Failure
    }
}

/**
 * Validation error for a specific field.
 */
public data class ValidationError(
    val field: String,
    val message: String,
    val code: String? = null
)

/**
 * Represents a set of changes made to a user.
 */
public data class ChangeSet(
    val timestamp: Instant,
    val changedFields: Map<String, FieldChange>
) {
    /**
     * Returns true if the specified field was changed.
     */
    public fun hasFieldChange(fieldName: String): Boolean = fieldName in changedFields

    /**
     * Returns the change for the specified field, or null if not changed.
     */
    public fun getFieldChange(fieldName: String): FieldChange? = changedFields[fieldName]

    /**
     * Returns a list of all changed field names.
     */
    public fun changedFieldNames(): List<String> = changedFields.keys.toList()
}

/**
 * Represents a change to a single field.
 */
public data class FieldChange(
    val fieldName: String,
    val oldValue: Any?,
    val newValue: Any?
) {
    /**
     * Returns true if the value actually changed.
     */
    public fun hasChanged(): Boolean = oldValue != newValue

    /**
     * Returns a human-readable description of the change.
     */
    override fun toString(): String = "$fieldName: $oldValue → $newValue"
}

/**
 * Extension function to convert UpdateResult.Success to a Result.
 */
public fun UpdateResult.Success.toResult(): Result<FullUser> = Result.success(user)

/**
 * Extension function to convert UpdateResult.Failure to a Result.
 */
public fun UpdateResult.Failure.toResult(): Result<FullUser> = Result.failure(
    when (this) {
        is UpdateResult.Failure.NotFound -> Exception("User not found: $userId")
        is UpdateResult.Failure.ValidationFailed -> Exception("Validation failed: ${errors.joinToString()}")
        is UpdateResult.Failure.ConstraintViolation -> Exception("Constraint violation on $field: $reason")
        is UpdateResult.Failure.Unknown -> cause ?: Exception(message)
    }
)

/**
 * Extension function to get the user from an UpdateResult or throw if failed.
 */
public fun UpdateResult.userOrThrow(): FullUser = when (this) {
    is UpdateResult.Success -> user
    is UpdateResult.Failure.NotFound -> throw Exception("User not found: $userId")
    is UpdateResult.Failure.ValidationFailed -> throw Exception("Validation failed: ${errors.joinToString()}")
    is UpdateResult.Failure.ConstraintViolation -> throw Exception("Constraint violation on $field: $reason")
    is UpdateResult.Failure.Unknown -> throw (cause ?: Exception(message))
}
