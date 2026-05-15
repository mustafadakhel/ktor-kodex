package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.lockout.schema.LockoutSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
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

            val recentAttempts = failedAttempts.selectAll().where {
                (failedAttempts.realmId eq this@DefaultAccountLockoutService.realmId) and
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

            val recentAttempts = failedAttempts.selectAll().where {
                (failedAttempts.realmId eq this@DefaultAccountLockoutService.realmId) and
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

            failedAttempts.deleteWhere {
                (failedAttempts.realmId eq this@DefaultAccountLockoutService.realmId) and
                (failedAttempts.identifier eq identifier) and
                (failedAttempts.attemptedAt less windowStart)
            }

            failedAttempts.insert {
                it[failedAttempts.realmId] = this@DefaultAccountLockoutService.realmId
                it[failedAttempts.identifier] = identifier
                it[failedAttempts.userId] = userId
                it[failedAttempts.ipAddress] = ipAddress
                it[failedAttempts.attemptedAt] = nowLocal
                it[failedAttempts.reason] = reason
            }
        }
    }

    override fun shouldLockAccount(userId: UUID): LockAccountResult {
        if (!policy.enabled) return LockAccountResult.NoAction

        return db.transaction {
            val clockNow = CurrentKotlinInstant
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            val accountAttempts = failedAttempts.selectAll().where {
                (failedAttempts.realmId eq this@DefaultAccountLockoutService.realmId) and
                (failedAttempts.userId eq userId) and
                (failedAttempts.attemptedAt greater windowStart)
            }.count()

            if (accountAttempts >= policy.maxFailedAttempts) {
                val lockedUntil = (clockNow + policy.lockoutDuration).toLocalDateTime(timeZone)
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
            failedAttempts.deleteWhere {
                (failedAttempts.realmId eq this@DefaultAccountLockoutService.realmId) and (failedAttempts.identifier eq identifier)
            }
        }
    }

    override fun clearFailedAttemptsForUser(userId: UUID) {
        db.transaction {
            failedAttempts.deleteWhere {
                (failedAttempts.realmId eq this@DefaultAccountLockoutService.realmId) and (failedAttempts.userId eq userId)
            }
        }
    }

    override fun lockAccount(userId: UUID, lockedUntil: LocalDateTime, reason: String) {
        db.transaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)

            locks.deleteWhere {
                (locks.realmId eq this@DefaultAccountLockoutService.realmId) and (locks.userId eq userId)
            }
            locks.insert {
                it[locks.realmId] = this@DefaultAccountLockoutService.realmId
                it[locks.userId] = userId
                it[locks.lockedUntil] = lockedUntil
                it[locks.reason] = reason
                it[locks.lockedAt] = nowLocal
            }
        }
    }

    override fun unlockAccount(userId: UUID) {
        db.transaction {
            locks.deleteWhere {
                (locks.realmId eq this@DefaultAccountLockoutService.realmId) and (locks.userId eq userId)
            }
        }
    }

    override fun isAccountLocked(userId: UUID, currentTime: LocalDateTime): Boolean {
        return db.transaction {
            locks.selectAll().where {
                (locks.realmId eq this@DefaultAccountLockoutService.realmId) and (locks.userId eq userId)
            }
                .singleOrNull()
                ?.let { row ->
                    val lockedUntil = row[locks.lockedUntil]
                    lockedUntil == null || lockedUntil > currentTime
                } ?: false
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
