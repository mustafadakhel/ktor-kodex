package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.lockout.database.AccountLocks
import com.mustafadakhel.kodex.lockout.database.FailedLoginAttempts
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.now
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Table
import java.util.*

/**
 * Account lockout extension that protects against brute force attacks.
 * Implements OWASP/NIST compliant two-layer protection:
 * - Layer 1 (beforeLogin): Throttling based on identifier + IP
 * - Layer 2 (afterAuthentication): Account lockout for real accounts only
 *
 * Priority: 10 (critical) - runs early to block throttled requests and locked accounts.
 */
public class AccountLockoutExtension internal constructor(
    private val service: AccountLockoutService,
    private val userRepository: UserRepository,
    private val timeZone: TimeZone
) : UserLifecycleHooks, PersistentExtension {

    override val priority: Int = 10

    override fun tables(): List<Table> = listOf(
        FailedLoginAttempts,
        AccountLocks
    )

    override suspend fun beforeLogin(identifier: String, metadata: LoginMetadata): String {
        // Layer 1: Check throttling (identifier + IP based)
        val identifierThrottle = service.shouldThrottleIdentifier(identifier)
        if (identifierThrottle is ThrottleResult.Throttled) {
            throw KodexThrowable.Authorization.TooManyAttempts(
                reason = identifierThrottle.reason
            )
        }

        val ipThrottle = service.shouldThrottleIp(metadata.ipAddress)
        if (ipThrottle is ThrottleResult.Throttled) {
            throw KodexThrowable.Authorization.TooManyAttempts(
                reason = ipThrottle.reason
            )
        }

        return identifier
    }

    override suspend fun afterAuthentication(userId: UUID) {
        // Layer 2: Check if account is locked or should be locked
        val nowLocal = now(timeZone)

        // First check if account is already locked
        if (service.isAccountLocked(userId, nowLocal)) {
            // Account is already locked - block login
            throw KodexThrowable.Authorization.AccountLocked(
                lockedUntil = nowLocal,  // We don't have exact time but nowLocal is safe
                reason = "Account is locked due to too many failed login attempts"
            )
        }

        // Check if account should be locked based on recent failures
        val lockResult = service.shouldLockAccount(userId)
        if (lockResult is LockAccountResult.ShouldLock) {
            service.lockAccount(
                userId = userId,
                lockedUntil = lockResult.lockedUntil,
                reason = "Account locked due to ${lockResult.attemptCount} failed login attempts"
            )
            throw KodexThrowable.Authorization.AccountLocked(
                lockedUntil = lockResult.lockedUntil,
                reason = "Account locked due to ${lockResult.attemptCount} failed login attempts"
            )
        }
    }

    override suspend fun afterLoginFailure(identifier: String, metadata: LoginMetadata) {
        // Record failed attempt with all metadata
        // userId will be null if account doesn't exist (prevents username enumeration)
        val user = userRepository.findByEmail(identifier) ?: userRepository.findByPhone(identifier)
        service.recordFailedAttempt(
            identifier = identifier,
            userId = user?.id,
            ipAddress = metadata.ipAddress,
            reason = "Invalid credentials"
        )
    }

    override suspend fun beforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData = UserCreateData(email, phone, customAttributes, profile)

    override suspend fun beforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData = UserUpdateData(email, phone)

    override suspend fun beforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> = customAttributes
}
