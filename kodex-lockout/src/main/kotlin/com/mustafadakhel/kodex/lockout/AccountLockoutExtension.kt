package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.lockout.database.AccountLockouts
import com.mustafadakhel.kodex.lockout.database.FailedLoginAttempts
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.throwable.KodexThrowable
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Table
import java.util.*

/**
 * Account lockout extension that protects against brute force attacks.
 * Tracks failed login attempts and automatically locks accounts when thresholds are exceeded.
 *
 * Priority: 10 (critical) - runs early to block locked accounts before other checks.
 */
public class AccountLockoutExtension internal constructor(
    private val service: AccountLockoutService,
    private val timeZone: TimeZone
) : UserLifecycleHooks, PersistentExtension {

    override val priority: Int = 10

    override fun tables(): List<Table> = listOf(
        FailedLoginAttempts,
        AccountLockouts
    )

    override suspend fun beforeLogin(identifier: String): String {
        // Check if account is locked before allowing login attempt
        val lockoutResult = service.checkLockout(identifier, timeZone)

        if (lockoutResult is LockoutResult.Locked) {
            throw KodexThrowable.Authorization.AccountLocked(
                lockedUntil = lockoutResult.lockedUntil,
                reason = lockoutResult.reason
            )
        }

        return identifier
    }

    override suspend fun afterLoginFailure(identifier: String) {
        // Record failed attempt (may trigger lockout)
        service.recordFailedAttempt(identifier, "Invalid credentials")
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
