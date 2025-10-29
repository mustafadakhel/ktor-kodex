package com.mustafadakhel.kodex.service.auth

import com.mustafadakhel.kodex.event.AuthEvent
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.util.now
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import java.util.UUID

/**
 * Default implementation of AuthenticationService.
 *
 * This implementation provides secure authentication flows with:
 * - Constant-time password verification (timing attack prevention)
 * - Dummy hash verification when user doesn't exist
 * - Hook execution for extensibility (account lockout, rate limiting)
 * - Comprehensive audit event publishing
 */
internal class DefaultAuthenticationService(
    private val userRepository: UserRepository,
    private val hashingService: HashingService,
    private val tokenService: TokenService,
    private val hookExecutor: HookExecutor,
    private val eventBus: EventBus,
    private val timeZone: TimeZone,
    private val realm: Realm
) : AuthenticationService {

    // Dummy hash for constant-time verification when user doesn't exist
    // Prevents timing attacks that reveal whether a user exists
    private val dummyHash = hashingService.hash("dummy-password-for-timing-attack-prevention")

    override suspend fun tokenByEmail(email: String, password: String): TokenPair =
        authenticateAndGenerateToken(
            identifier = email,
            password = password,
            identifierType = "email",
            userFetcher = { userRepository.findByEmail(it) }
        )

    override suspend fun tokenByPhone(phone: String, password: String): TokenPair =
        authenticateAndGenerateToken(
            identifier = phone,
            password = password,
            identifierType = "phone",
            userFetcher = { userRepository.findByPhone(it) }
        )

    override suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String) {
        val timestamp = Clock.System.now()

        // Verify user exist
        val user = userRepository.findById(userId) ?: throw KodexThrowable.UserNotFound("User with id $userId not found")

        // Verify old password
        if (!authenticateInternal(oldPassword, userId)) {
            // Publish event
            eventBus.publish(
                AuthEvent.PasswordChangeFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = timestamp,
                    realmId = realm.owner,
                    userId = userId,
                    actorId = userId,
                    reason = "Invalid old password"
                )
            )
            throw KodexThrowable.Authorization.InvalidCredentials
        }

        // Hash new password
        val hashedPassword = hashingService.hash(newPassword)

        // Update password
        val success = userRepository.updatePassword(userId, hashedPassword)
        if (!success) {
            throw KodexThrowable.UserNotFound("User with id $userId not found")
        }

        // Publish event
        eventBus.publish(
            AuthEvent.PasswordChanged(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorId = userId
            )
        )
    }

    override suspend fun resetPassword(userId: UUID, newPassword: String) {
        val timestamp = Clock.System.now()

        // Verify user exists
        val user = userRepository.findById(userId) ?: throw KodexThrowable.UserNotFound("User with id $userId not found")

        // Hash new password
        val hashedPassword = hashingService.hash(newPassword)

        // Update password
        val success = userRepository.updatePassword(userId, hashedPassword)
        if (!success) {
            throw KodexThrowable.UserNotFound("User with id $userId not found")
        }

        // Publish event
        eventBus.publish(
            AuthEvent.PasswordReset(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = userId,
                actorType = "ADMIN"
            )
        )
    }

    /**
     * Common authentication logic for both email and phone-based login.
     * Prevents information leakage by using identical error paths for security.
     */
    private suspend fun authenticateAndGenerateToken(
        identifier: String,
        password: String,
        identifierType: String,
        userFetcher: suspend (String) -> UserEntity?
    ): TokenPair {
        // Capture timestamp once for consistency across all audit events
        val timestamp = Clock.System.now()

        // Execute beforeLogin hooks (e.g., lockout check)
        hookExecutor.executeBeforeLogin(identifier)

        val user = userFetcher(identifier)

        // Security: ALWAYS verify password to prevent timing attacks
        // When user doesn't exist, verify against dummy hash to maintain constant timing
        val authSuccess = if (user != null) {
            authenticateInternal(password, user.id)
        } else {
            // Perform dummy verification to prevent timing-based user enumeration
            hashingService.verify(password, dummyHash)
            false
        }

        if (!authSuccess) {
            // Execute afterLoginFailure hooks
            hookExecutor.executeAfterLoginFailure(identifier)

            // Audit failed login (detailed reason only in server logs)
            val actualReason = when {
                user == null -> "User not found"
                else -> "Invalid password"
            }

            // Publish event
            eventBus.publish(
                AuthEvent.LoginFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = timestamp,
                    realmId = realm.owner,
                    identifier = identifier,
                    reason = actualReason,
                    method = identifierType,
                    userId = user?.id,
                    actorType = if (user == null) "ANONYMOUS" else "USER"
                )
            )

            // Always throw same exception regardless of reason
            throw KodexThrowable.Authorization.InvalidCredentials
        }

        // Check verification status
        if (!user!!.isVerified) {
            throw KodexThrowable.Authorization.UnverifiedAccount
        }

        // Update last login time
        userRepository.updateLastLogin(user.id, now(timeZone))

        // Publish event
        eventBus.publish(
            AuthEvent.LoginSuccess(
                eventId = UUID.randomUUID(),
                timestamp = timestamp,
                realmId = realm.owner,
                userId = user.id,
                identifier = identifier,
                method = identifierType
            )
        )

        return generateTokenInternal(user.id)
    }

    private fun authenticateInternal(password: String, userId: UUID): Boolean {
        val storedPassword = userRepository.getHashedPassword(userId) ?: return false
        return hashingService.verify(password, storedPassword)
    }

    private suspend fun generateTokenInternal(userId: UUID): TokenPair {
        return tokenService.issueTokens(userId)
    }
}
