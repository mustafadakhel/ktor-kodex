@file:Suppress("unused")

package com.mustafadakhel.kodex.throwable

import java.util.UUID

public open class KodexThrowable(
    message: String? = null,
    cause: Throwable? = null,
    public open val clientMessage: String? = null
) : Exception(message, cause) {
    public data class EmailAlreadyExists(
        override val cause: Throwable? = null,
    ) : KodexThrowable("Email already exists", cause, clientMessage = "Email already exists")

    public data class PhoneAlreadyExists(
        override val cause: Throwable? = null,
    ) : KodexThrowable("Phone number already exists", cause, clientMessage = "Phone number already exists")

    public data class Unknown(
        override val message: String? = null,
        override val cause: Throwable? = null
    ) : KodexThrowable(message, cause, clientMessage = "An unexpected error occurred")

    public data class UserNotFound(
        override val message: String? = null,
        override val cause: Throwable? = null
    ) : KodexThrowable(message, cause, clientMessage = "User not found")

    public data class UserUpdateFailed(
        private val userId: UUID,
    ) : KodexThrowable("Failed to update user with ID: $userId", clientMessage = "Failed to update user")

    public data class ProfileNotFound(
        private val userId: UUID,
    ) : KodexThrowable("Profile not found for user with ID: $userId", clientMessage = "Profile not found")

    public sealed class Database(
        message: String? = null,
        cause: Throwable? = null,
        override val clientMessage: String? = "A database error occurred"
    ) : KodexThrowable(message, cause, clientMessage) {
        public data class Unknown(
            override val message: String? = null,
            override val cause: Throwable? = null
        ) : Database(message, cause)
    }

    public sealed class Authorization(
        message: String? = null,
        override val clientMessage: String? = "Authorization failed"
    ) : KodexThrowable(message, clientMessage = clientMessage) {
        public data class SuspiciousToken(
            val additionalInfo: String? = null
        ) : Authorization("Suspicious token: $additionalInfo", clientMessage = "Invalid token")

        public data object InvalidCredentials : Authorization("Invalid credentials", clientMessage = "Invalid credentials") {
            private fun readResolve(): Any = InvalidCredentials
        }

        public data object UserRoleNotFound : Authorization("User role not found", clientMessage = "Insufficient permissions") {
            private fun readResolve(): Any = UserRoleNotFound
        }

        public data object UserHasNoRoles : Authorization("User has no roles assigned", clientMessage = "Insufficient permissions") {
            private fun readResolve(): Any = UserHasNoRoles
        }

        public data class InvalidToken(
            val additionalInfo: String? = null
        ) : Authorization("Invalid token: $additionalInfo", clientMessage = "Invalid token")

        public data class TokenReplayDetected(
            val tokenFamily: UUID,
            val originalTokenId: UUID
        ) : Authorization(
            "Refresh token replay attack detected. Token family $tokenFamily has been revoked.",
            clientMessage = "Session has been invalidated for security reasons"
        )

        public data class InsufficientPermissions(
            val requiredRole: String,
            val userId: UUID
        ) : Authorization(
            "User $userId does not have the required '$requiredRole' role",
            clientMessage = "Insufficient permissions"
        )
    }

    public data class RoleNotFound(
        public val roleName: String,
        override val cause: Throwable? = null,
    ) : KodexThrowable("Role not found: $roleName", cause, clientMessage = "Role not found")

    public open class Validation(
        message: String? = null,
        cause: Throwable? = null,
        override val clientMessage: String? = message
    ) : KodexThrowable(message, cause, clientMessage)
}
