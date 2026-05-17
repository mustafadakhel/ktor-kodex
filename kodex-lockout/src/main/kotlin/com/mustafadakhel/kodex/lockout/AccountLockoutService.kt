@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.greater
import com.mustafadakhel.kodex.jdbc.less
import com.mustafadakhel.kodex.lockout.schema.LockoutSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

internal interface AccountLockoutService {
    fun shouldThrottleIdentifier(identifier: String): ThrottleResult
    fun shouldThrottleIp(ipAddress: String): ThrottleResult
    fun recordFailedAttempt(identifier: String, userId: UUID?, ipAddress: String, reason: String)
    fun shouldLockAccount(userId: UUID): LockAccountResult
    fun clearFailedAttemptsForIdentifier(identifier: String)
    fun clearFailedAttemptsForUser(userId: UUID)
    fun lockAccount(userId: UUID, lockedUntil: LocalDateTime, reason: String)
    fun unlockAccount(userId: UUID)
    fun isAccountLocked(userId: UUID, currentTime: LocalDateTime): Boolean
    fun sweepOldAttempts(olderThan: LocalDateTime): Int
}

internal class DefaultAccountLockoutService(
    private val db: KodexDatabase,
    private val schema: LockoutSchema,
    private val policy: AccountLockoutPolicy,
    private val timeZone: TimeZone,
    private val realmId: String
) : AccountLockoutService {

    private val failedAttempts = schema.failedLoginAttempts
    private val locks = schema.accountLocks

    override fun shouldThrottleIdentifier(identifier: String): ThrottleResult {
        if (!policy.enabled) return ThrottleResult.NotThrottled

        return db.transaction {
            val clockNow = CurrentKotlinInstant
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            val recentAttempts = select(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and
                    (failedAttempts.identifier eq identifier) and
                    (failedAttempts.attemptedAt greater windowStart)
            }.count()

            if (recentAttempts >= policy.maxFailedAttempts) {
                ThrottleResult.Throttled(
                    reason = "Too many failed attempts for this identifier",
                    attemptCount = recentAttempts.toInt()
                )
            } else {
                ThrottleResult.NotThrottled
            }
        }
    }

    override fun shouldThrottleIp(ipAddress: String): ThrottleResult {
        if (!policy.enabled) return ThrottleResult.NotThrottled

        return db.transaction {
            val clockNow = CurrentKotlinInstant
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            val recentAttempts = select(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and
                    (failedAttempts.ipAddress eq ipAddress) and
                    (failedAttempts.attemptedAt greater windowStart)
            }.count()

            val ipThreshold = policy.maxFailedAttempts * 4
            if (recentAttempts >= ipThreshold) {
                ThrottleResult.Throttled(
                    reason = "Too many failed attempts from this IP",
                    attemptCount = recentAttempts.toInt()
                )
            } else {
                ThrottleResult.NotThrottled
            }
        }
    }

    override fun recordFailedAttempt(
        identifier: String,
        userId: UUID?,
        ipAddress: String,
        reason: String
    ) {
        if (!policy.enabled) return

        db.transaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            deleteFrom(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and
                    (failedAttempts.identifier eq identifier) and
                    (failedAttempts.attemptedAt less windowStart)
            }.execute()

            insertInto(failedAttempts) {
                this[failedAttempts.realmId] = realmId
                this[failedAttempts.identifier] = identifier
                this[failedAttempts.userId] = userId
                this[failedAttempts.ipAddress] = ipAddress
                this[failedAttempts.attemptedAt] = nowLocal
                this[failedAttempts.reason] = reason
            }
        }
    }

    override fun shouldLockAccount(userId: UUID): LockAccountResult {
        if (!policy.enabled) return LockAccountResult.NoAction

        return db.transaction {
            val clockNow = CurrentKotlinInstant
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            val accountAttempts = select(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and
                    (failedAttempts.userId eq userId) and
                    (failedAttempts.attemptedAt greater windowStart)
            }.count()

            if (accountAttempts >= policy.maxFailedAttempts) {
                val lockedUntil = (clockNow + policy.lockoutDuration).toLocalDateTime(TimeZone.UTC)
                LockAccountResult.ShouldLock(
                    lockedUntil = lockedUntil,
                    attemptCount = accountAttempts.toInt()
                )
            } else {
                LockAccountResult.NoAction
            }
        }
    }

    override fun clearFailedAttemptsForIdentifier(identifier: String) {
        db.transaction {
            deleteFrom(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and (failedAttempts.identifier eq identifier)
            }.execute()
        }
    }

    override fun clearFailedAttemptsForUser(userId: UUID) {
        db.transaction {
            deleteFrom(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and (failedAttempts.userId eq userId)
            }.execute()
        }
    }

    override fun lockAccount(userId: UUID, lockedUntil: LocalDateTime, reason: String) {
        db.transaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)

            deleteFrom(locks).where {
                (locks.realmId eq realmId) and (locks.userId eq userId)
            }.execute()

            insertInto(locks) {
                this[locks.realmId] = realmId
                this[locks.userId] = userId
                this[locks.lockedUntil] = lockedUntil
                this[locks.reason] = reason
                this[locks.lockedAt] = nowLocal
            }
        }
    }

    override fun unlockAccount(userId: UUID) {
        db.transaction {
            deleteFrom(locks).where {
                (locks.realmId eq realmId) and (locks.userId eq userId)
            }.execute()
        }
    }

    override fun isAccountLocked(userId: UUID, currentTime: LocalDateTime): Boolean {
        return db.transaction {
            select(locks).where {
                (locks.realmId eq realmId) and (locks.userId eq userId)
            }.singleOrNull { row ->
                val lockedUntil = row[locks.lockedUntil]
                lockedUntil == null || lockedUntil > currentTime
            } ?: false
        }
    }

    override fun sweepOldAttempts(olderThan: LocalDateTime): Int {
        return db.transaction {
            deleteFrom(failedAttempts).where {
                (failedAttempts.realmId eq realmId) and (failedAttempts.attemptedAt less olderThan)
            }.execute()
        }
    }
}

internal fun accountLockoutService(
    db: KodexDatabase,
    schema: LockoutSchema,
    policy: AccountLockoutPolicy,
    timeZone: TimeZone,
    realmId: String
): AccountLockoutService =
    DefaultAccountLockoutService(db, schema, policy, timeZone, realmId)
