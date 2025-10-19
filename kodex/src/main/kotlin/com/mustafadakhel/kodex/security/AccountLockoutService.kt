package com.mustafadakhel.kodex.security

import com.mustafadakhel.kodex.model.database.AccountLockoutDao
import com.mustafadakhel.kodex.model.database.AccountLockouts
import com.mustafadakhel.kodex.model.database.FailedLoginAttemptDao
import com.mustafadakhel.kodex.model.database.FailedLoginAttempts
import com.mustafadakhel.kodex.util.exposedTransaction
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

internal interface AccountLockoutService {
    fun recordFailedAttempt(identifier: String, ipAddress: String, userAgent: String?, reason: String)
    fun checkLockout(identifier: String, timeZone: TimeZone): LockoutResult
    fun unlockAccount(identifier: String)
    fun clearFailedAttempts(identifier: String)
}

internal class DefaultAccountLockoutService(
    private val policy: AccountLockoutPolicy
) : AccountLockoutService {

    override fun recordFailedAttempt(
        identifier: String,
        ipAddress: String,
        userAgent: String?,
        reason: String
    ) {
        if (!policy.enabled) return

        exposedTransaction {
            val clockNow = Clock.System.now()
            val now = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            // Clean old attempts only for this specific identifier
            FailedLoginAttempts.deleteWhere {
                (FailedLoginAttempts.identifier eq identifier) and
                        (FailedLoginAttempts.attemptedAt less windowStart)
            }

            // Record the new failed attempt
            FailedLoginAttemptDao.new {
                this.identifier = identifier
                this.ipAddress = ipAddress
                this.userAgent = userAgent
                this.attemptedAt = now
                this.reason = reason
            }

            // Count recent attempts (within the window) for this identifier
            val recentAttempts = FailedLoginAttemptDao.find {
                (FailedLoginAttempts.identifier eq identifier) and
                        (FailedLoginAttempts.attemptedAt greater windowStart)
            }.count()

            if (recentAttempts >= policy.maxFailedAttempts) {
                lockAccount(identifier, recentAttempts.toInt(), now)
            }
        }
    }

    override fun checkLockout(identifier: String, timeZone: TimeZone): LockoutResult {
        if (!policy.enabled) return LockoutResult.NotLocked

        return exposedTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            val lockout = AccountLockoutDao.find {
                AccountLockouts.identifier eq identifier
            }.firstOrNull()

            if (lockout == null) {
                return@exposedTransaction LockoutResult.NotLocked
            }

            if (lockout.lockedUntil <= now) {
                lockout.delete()
                return@exposedTransaction LockoutResult.NotLocked
            }

            LockoutResult.Locked(
                lockedUntil = lockout.lockedUntil,
                reason = lockout.reason,
                failedAttemptCount = lockout.failedAttemptCount
            )
        }
    }

    override fun unlockAccount(identifier: String) {
        exposedTransaction {
            AccountLockouts.deleteWhere { AccountLockouts.identifier eq identifier }
            FailedLoginAttempts.deleteWhere { FailedLoginAttempts.identifier eq identifier }
        }
    }

    override fun clearFailedAttempts(identifier: String) {
        exposedTransaction {
            FailedLoginAttempts.deleteWhere { FailedLoginAttempts.identifier eq identifier }
        }
    }

    private fun lockAccount(identifier: String, attemptCount: Int, lockedAt: kotlinx.datetime.LocalDateTime) {
        val lockedUntil = (Clock.System.now() + policy.lockoutDuration).toLocalDateTime(TimeZone.UTC)

        val existing = AccountLockoutDao.find {
            AccountLockouts.identifier eq identifier
        }.firstOrNull()

        if (existing != null) {
            existing.lockedUntil = lockedUntil
            existing.failedAttemptCount = attemptCount
            existing.lockedAt = lockedAt
        } else {
            AccountLockoutDao.new {
                this.identifier = identifier
                this.lockedAt = lockedAt
                this.lockedUntil = lockedUntil
                this.reason = "Too many failed login attempts ($attemptCount)"
                this.failedAttemptCount = attemptCount
            }
        }
    }
}

internal fun accountLockoutService(policy: AccountLockoutPolicy): AccountLockoutService =
    DefaultAccountLockoutService(policy)
