package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.lockout.schema.LockoutSchema
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.DatabaseAwareExtension
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.now
import kotlinx.datetime.TimeZone
import java.util.*

public class AccountLockoutExtension internal constructor(
    private val policy: AccountLockoutPolicy,
    private val timeZone: TimeZone,
    private val realmId: String
) : UserLifecycleHooks, PersistentExtension, DatabaseAwareExtension {

    override val priority: Int = 10

    private lateinit var service: AccountLockoutService

    override fun createSchema(core: CoreSchema): ExtensionSchema = LockoutSchema(core)

    override fun initialize(db: KodexDatabase) {
        val schema = db.schema<LockoutSchema>()
        service = accountLockoutService(db, schema, policy, timeZone, realmId)
    }

    override suspend fun beforeLogin(identifier: String, metadata: LoginMetadata): String {
        val identifierThrottle = service.shouldThrottleIdentifier(identifier)
        if (identifierThrottle is ThrottleResult.Throttled) {
            throw LockoutThrowable.TooManyAttempts(
                reason = identifierThrottle.reason
            )
        }

        val ipThrottle = service.shouldThrottleIp(metadata.ipAddress)
        if (ipThrottle is ThrottleResult.Throttled) {
            throw LockoutThrowable.TooManyAttempts(
                reason = ipThrottle.reason
            )
        }

        return identifier
    }

    override suspend fun afterAuthentication(user: AuthenticatedUser, metadata: LoginMetadata) {
        val nowLocal = now(timeZone)

        if (service.isAccountLocked(user.userId, nowLocal)) {
            throw LockoutThrowable.AccountLocked(
                lockedUntil = nowLocal,
                reason = "Account is locked due to too many failed login attempts"
            )
        }

        val lockResult = service.shouldLockAccount(user.userId)
        if (lockResult is LockAccountResult.ShouldLock) {
            service.lockAccount(
                userId = user.userId,
                lockedUntil = lockResult.lockedUntil,
                reason = "Account locked due to ${lockResult.attemptCount} failed login attempts"
            )
            throw LockoutThrowable.AccountLocked(
                lockedUntil = lockResult.lockedUntil,
                reason = "Account locked due to ${lockResult.attemptCount} failed login attempts"
            )
        }
    }

    override suspend fun afterLoginFailure(
        identifier: String,
        userId: UUID?,
        identifierType: String,
        metadata: LoginMetadata
    ) {
        service.recordFailedAttempt(
            identifier = identifier,
            userId = userId,
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
