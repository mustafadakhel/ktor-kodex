package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.throwable.KodexThrowable

/**
 * Sealed result type for user creation operations.
 *
 * Provides a type-safe alternative to exception-based error handling
 * for [UserService.createUserSafe].
 */
public sealed interface CreateUserResult {
    public data class Success(val user: User) : CreateUserResult
    public data object EmailAlreadyExists : CreateUserResult
    public data object PhoneAlreadyExists : CreateUserResult
    public data class InvalidRole(val roleName: String) : CreateUserResult
    public data class ValidationFailed(val errors: List<String>) : CreateUserResult
    public data class Unknown(val message: String, val cause: Throwable? = null) : CreateUserResult
}

/**
 * Returns the [User] if this result is [CreateUserResult.Success], or throws
 * an appropriate [KodexThrowable].
 */
public fun CreateUserResult.userOrThrow(): User = when (this) {
    is CreateUserResult.Success -> user
    is CreateUserResult.EmailAlreadyExists -> throw KodexThrowable.EmailAlreadyExists()
    is CreateUserResult.PhoneAlreadyExists -> throw KodexThrowable.PhoneAlreadyExists()
    is CreateUserResult.InvalidRole -> throw KodexThrowable.RoleNotFound(roleName)
    is CreateUserResult.ValidationFailed -> throw KodexThrowable.Validation("Validation failed: ${errors.joinToString()}")
    is CreateUserResult.Unknown -> throw KodexThrowable.Unknown(message, cause)
}
