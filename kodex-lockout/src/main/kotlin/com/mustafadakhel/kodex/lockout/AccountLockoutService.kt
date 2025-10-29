package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.lockout.database.AccountLockouts
import com.mustafadakhel.kodex.lockout.database.FailedLoginAttempts
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less

/**
 * Service for managing account lockouts and failed login attempts.
 * Implements protection against brute force attacks.
 */
internal interface AccountLockoutService {
    fun recordFailedAttempt(identifier: String, reason: String)
    fun checkLockout(identifier: String, timeZone: TimeZone): LockoutResult
    fun unlockAccount(identifier: String)
    fun clearFailedAttempts(identifier: String)
}

/**
 * Default implementation of AccountLockoutService.
 * Tracks failed login attempts and automatically locks accounts when thresholds are exceeded.
 */
internal class DefaultAccountLockoutService(
    private val policy: AccountLockoutPolicy
) : AccountLockoutService {

    override fun recordFailedAttempt(
        identifier: String,
        reason: String
    ) {
        if (!policy.enabled) return

        kodexTransaction {
            val clockNow = Clock.System.now()
            val now = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            // Clean old attempts only for this specific identifier
            FailedLoginAttempts.deleteWhere {
                (FailedLoginAttempts.identifier eq identifier) and
                        (FailedLoginAttempts.attemptedAt less windowStart)
            }

            // Record the new failed attempt
            FailedLoginAttempts.insert {
                it[FailedLoginAttempts.identifier] = identifier
                it[attemptedAt] = now
                it[FailedLoginAttempts.reason] = reason
            }

            // Count recent attempts (within the window) for this identifier
            val recentAttempts = FailedLoginAttempts.selectAll().where {
                (FailedLoginAttempts.identifier eq identifier) and
                        (FailedLoginAttempts.attemptedAt greater windowStart)
            }.count()

            if (recentAttempts >= policy.maxFailedAttempts) {
                lockAccount(identifier, recentAttempts.toInt(), now, clockNow)
            }
        }
    }

    override fun checkLockout(identifier: String, timeZone: TimeZone): LockoutResult {
        if (!policy.enabled) return LockoutResult.NotLocked

        return kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            val lockout = AccountLockouts.selectAll().where {
                AccountLockouts.identifier eq identifier
            }.singleOrNull()

            if (lockout == null) {
                return@kodexTransaction LockoutResult.NotLocked
            }

            val lockedUntil = lockout[AccountLockouts.lockedUntil]
            if (lockedUntil <= now) {
                AccountLockouts.deleteWhere { AccountLockouts.identifier eq identifier }
                return@kodexTransaction LockoutResult.NotLocked
            }

            LockoutResult.Locked(
                lockedUntil = lockedUntil,
                reason = lockout[AccountLockouts.reason],
                failedAttemptCount = lockout[AccountLockouts.failedAttemptCount]
            )
        }
    }

    override fun unlockAccount(identifier: String) {
        kodexTransaction {
            AccountLockouts.deleteWhere { AccountLockouts.identifier eq identifier }
            FailedLoginAttempts.deleteWhere { FailedLoginAttempts.identifier eq identifier }
        }
    }

    override fun clearFailedAttempts(identifier: String) {
        kodexTransaction {
            FailedLoginAttempts.deleteWhere { FailedLoginAttempts.identifier eq identifier }
        }
    }

    private fun lockAccount(identifier: String, attemptCount: Int, lockedAt: kotlinx.datetime.LocalDateTime, clockNow: Instant) {
        val lockedUntil = (clockNow + policy.lockoutDuration).toLocalDateTime(TimeZone.UTC)

        val existing = AccountLockouts.selectAll().where {
            AccountLockouts.identifier eq identifier
        }.singleOrNull()

        if (existing != null) {
            AccountLockouts.update({ AccountLockouts.identifier eq identifier }) {
                it[AccountLockouts.lockedUntil] = lockedUntil
                it[failedAttemptCount] = attemptCount
                it[AccountLockouts.lockedAt] = lockedAt
            }
        } else {
            AccountLockouts.insert {
                it[AccountLockouts.identifier] = identifier
                it[AccountLockouts.lockedAt] = lockedAt
                it[AccountLockouts.lockedUntil] = lockedUntil
                it[reason] = "Too many failed login attempts ($attemptCount)"
                it[failedAttemptCount] = attemptCount
            }
        }
    }
}

internal fun accountLockoutService(policy: AccountLockoutPolicy): AccountLockoutService =
    DefaultAccountLockoutService(policy)
