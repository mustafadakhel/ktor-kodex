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
import com.mustafadakhel.kodex.util.now as nowLocal
import kotlinx.datetime.TimeZone
import java.util.UUID

/**
 * Default implementation of AuthService.
 *
 * Provides secure authentication flows with:
 * - Constant-time password verification (timing attack prevention)
 * - Dummy hash verification when user doesn't exist
 * - Hook execution for extensibility (account lockout, rate limiting)
 * - Comprehensive audit event publishing
 */
internal class DefaultAuthService(
    private val userRepository: UserRepository,
    private val hashingService: HashingService,
    private val tokenService: TokenService,
    private val hookExecutor: HookExecutor,
    private val eventBus: EventBus,
    private val timeZone: TimeZone,
    private val realm: Realm
) : AuthService {

    private val dummyHash = hashingService.hash("dummy-password-for-timing-attack-prevention")

    override suspend fun login(
        email: String,
        password: String,
        ipAddress: String,
        userAgent: String?
    ): TokenPair =
        authenticateAndGenerateToken(
            identifier = email,
            password = password,
            identifierType = "email",
            ipAddress = ipAddress,
            userAgent = userAgent,
            userFetcher = { userRepository.findByEmail(it) }
        )

    override suspend fun loginByPhone(
        phone: String,
        password: String,
        ipAddress: String,
        userAgent: String?
    ): TokenPair =
        authenticateAndGenerateToken(
            identifier = phone,
            password = password,
            identifierType = "phone",
            ipAddress = ipAddress,
            userAgent = userAgent,
            userFetcher = { userRepository.findByPhone(it) }
        )

    override suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String) {
        val timestamp = com.mustafadakhel.kodex.util.CurrentKotlinInstant

        val user = userRepository.findById(userId)
            ?: throw KodexThrowable.UserNotFound("User with id $userId not found")

        if (!authenticateInternal(oldPassword, userId)) {
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

        val hashedPassword = hashingService.hash(newPassword)

        val success = userRepository.updatePassword(userId, hashedPassword)
        if (!success) {
            throw KodexThrowable.UserNotFound("User with id $userId not found")
        }

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
        val timestamp = com.mustafadakhel.kodex.util.CurrentKotlinInstant

        val user = userRepository.findById(userId)
            ?: throw KodexThrowable.UserNotFound("User with id $userId not found")

        val hashedPassword = hashingService.hash(newPassword)

        val success = userRepository.updatePassword(userId, hashedPassword)
        if (!success) {
            throw KodexThrowable.UserNotFound("User with id $userId not found")
        }

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

    private suspend fun authenticateAndGenerateToken(
        identifier: String,
        password: String,
        identifierType: String,
        ipAddress: String,
        userAgent: String?,
        userFetcher: suspend (String) -> UserEntity?
    ): TokenPair {
        val timestamp = com.mustafadakhel.kodex.util.CurrentKotlinInstant
        val metadata = com.mustafadakhel.kodex.extension.LoginMetadata(ipAddress, userAgent)

        hookExecutor.executeBeforeLogin(identifier, metadata)

        val user = userFetcher(identifier)

        val authSuccess = if (user != null) {
            authenticateInternal(password, user.id)
        } else {
            hashingService.verify(password, dummyHash)
            false
        }

        if (!authSuccess) {
            hookExecutor.executeAfterLoginFailure(identifier, metadata)

            val actualReason = when {
                user == null -> "User not found"
                else -> "Invalid password"
            }

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

            throw KodexThrowable.Authorization.InvalidCredentials
        }

        hookExecutor.executeAfterAuthentication(user!!.id)

        userRepository.updateLastLogin(user.id, nowLocal(timeZone))

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
        return tokenService.issue(userId)
    }
}
