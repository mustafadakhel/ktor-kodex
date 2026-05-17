package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.extension.Shutdownable
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

public class AccountLockoutExtension internal constructor(
    private val service: AccountLockoutService,
    private val timeZone: TimeZone,
    private val sweepInterval: Duration = 1.hours,
    private val attemptRetentionPeriod: Duration = (7 * 24).hours
) : UserLifecycleHooks, Shutdownable {

    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var sweepJob: Job? = null

    init {
        sweepJob = sweepScope.launch {
            delay(sweepInterval)
            while (isActive) {
                try {
                    val cutoff = (CurrentKotlinInstant - attemptRetentionPeriod).toLocalDateTime(TimeZone.UTC)
                    service.sweepOldAttempts(cutoff)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
                delay(sweepInterval)
            }
        }
    }

    override fun shutdown() {
        sweepJob?.cancel()
        sweepScope.cancel()
    }

    override val priority: Int = 10

    override suspend fun beforeLogin(identifier: String, metadata: LoginMetadata): String {
        val identifierThrottle = service.shouldThrottleIdentifier(identifier.lowercase())
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
        val nowLocal = now(TimeZone.UTC)

        if (service.isAccountLocked(user.userId, nowLocal)) {
            throw LockoutThrowable.AccountLocked(
                lockedUntil = nowLocal,
                reason = "Account is locked due to too many failed login attempts"
            )
        }

        val lockResult = service.shouldLockAccount(user.userId)
        if (lockResult is LockAccountResult.NoAction) {
            service.clearFailedAttemptsForUser(user.userId)
            return
        }
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
            identifier = identifier.lowercase(),
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
