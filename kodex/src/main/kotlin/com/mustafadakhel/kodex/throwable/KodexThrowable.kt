@file:Suppress("unused")

package com.mustafadakhel.kodex.throwable

import java.util.UUID

public sealed class KodexThrowable(
    message: String? = null,
    cause: Throwable? = null
) : Throwable(message, cause) {
    public data class EmailAlreadyExists(
        override val cause: Throwable? = null,
    ) : KodexThrowable("Email already exists", cause)

    public data class PhoneAlreadyExists(
        override val cause: Throwable? = null,
    ) : KodexThrowable("Phone number already exists", cause)

    public data class Unknown(
        override val message: String? = null,
        override val cause: Throwable? = null
    ) : KodexThrowable(message, cause)

    public data class UserNotFound(
        override val message: String? = null,
        override val cause: Throwable? = null
    ) : KodexThrowable(message, cause)

    public data class UserUpdateFailed(
        private val userId: UUID,
    ) : KodexThrowable("Failed to update user with ID: $userId")

    public data class ProfileNotFound(
        private val userId: UUID,
    ) : KodexThrowable("Profile not found for user with ID: $userId")

    public sealed class Database(
        message: String? = null,
        cause: Throwable? = null
    ) : KodexThrowable(message, cause) {
        public data class Unknown(
            override val message: String? = null,
            override val cause: Throwable? = null
        ) : Database(message, cause)
    }

    public sealed class Authorization(
        message: String? = null,
    ) : KodexThrowable(message) {
        public data class SuspiciousToken(
            val additionalInfo: String? = null
        ) : Authorization("Suspicious token: $additionalInfo")

        public data object InvalidCredentials : Authorization("Invalid credentials") {
            private fun readResolve(): Any = InvalidCredentials
        }

        public data object UserRoleNotFound : Authorization("User role not found") {
            private fun readResolve(): Any = UserRoleNotFound
        }

        public data object UserHasNoRoles : Authorization("User has no roles assigned") {
            private fun readResolve(): Any = UserHasNoRoles
        }

        public data object UnverifiedAccount : Authorization("Account not verified") {
            private fun readResolve(): Any = UnverifiedAccount
        }

        public data class AccountLocked(
            val lockedUntil: kotlinx.datetime.LocalDateTime,
            val reason: String
        ) : Authorization("Account is locked until $lockedUntil. Reason: $reason")

        public data class InvalidToken(
            val additionalInfo: String? = null
        ) : Authorization("Invalid token: $additionalInfo")

        public data class TokenReplayDetected(
            val tokenFamily: UUID,
            val originalTokenId: UUID
        ) : Authorization("Refresh token replay attack detected. Token family $tokenFamily has been revoked.")
    }

    public data class RoleNotFound(
        public val roleName: String,
        override val cause: Throwable? = null,
    ) : KodexThrowable("Role not found: $roleName", cause)
}