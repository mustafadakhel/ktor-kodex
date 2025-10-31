package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.lockout.database.AccountLocks
import com.mustafadakhel.kodex.lockout.database.FailedLoginAttempts
import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.util.UUID

/**
 * Service for managing account lockouts and failed login attempts.
 * Implements OWASP/NIST compliant brute force protection with two layers:
 *
 * Layer 1: Throttling - applies to ALL attempts (identifier + IP based)
 * Layer 2: Lockout - applies only to real accounts (userId based)
 */
internal interface AccountLockoutService {
    /** Check if identifier should be throttled (Layer 1 - before auth) */
    fun shouldThrottleIdentifier(identifier: String): ThrottleResult

    /** Check if IP address should be throttled (Layer 1 - before auth) */
    fun shouldThrottleIp(ipAddress: String): ThrottleResult

    /** Record failed attempt with all metadata (identifier, userId, IP) */
    fun recordFailedAttempt(identifier: String, userId: UUID?, ipAddress: String, reason: String)

    /** Check if account should be locked based on failed attempts (Layer 2 - real accounts only) */
    fun shouldLockAccount(userId: UUID): LockAccountResult

    /** Clear failed attempts for identifier */
    fun clearFailedAttemptsForIdentifier(identifier: String)

    /** Clear failed attempts for user */
    fun clearFailedAttemptsForUser(userId: UUID)

    /** Lock an account until specified time */
    fun lockAccount(userId: UUID, lockedUntil: LocalDateTime, reason: String)

    /** Unlock an account */
    fun unlockAccount(userId: UUID)

    /** Check if account is currently locked */
    fun isAccountLocked(userId: UUID, currentTime: LocalDateTime): Boolean
}

/**
 * Default implementation of AccountLockoutService.
 * Implements two-layer brute force protection:
 * - Layer 1: Throttling (identifier + IP)
 * - Layer 2: Account lockout (real accounts only)
 */
internal class DefaultAccountLockoutService(
    private val policy: AccountLockoutPolicy,
    private val timeZone: TimeZone
) : AccountLockoutService {

    override fun shouldThrottleIdentifier(identifier: String): ThrottleResult {
        if (!policy.enabled) return ThrottleResult.NotThrottled

        return kodexTransaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            val recentAttempts = FailedLoginAttempts.selectAll().where {
                (FailedLoginAttempts.identifier eq identifier) and
                (FailedLoginAttempts.attemptedAt greater windowStart)
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

        return kodexTransaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            val recentAttempts = FailedLoginAttempts.selectAll().where {
                (FailedLoginAttempts.ipAddress eq ipAddress) and
                (FailedLoginAttempts.attemptedAt greater windowStart)
            }.count()

            // IP-based throttling typically has a higher threshold
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

        kodexTransaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            // Clean old attempts for this identifier
            FailedLoginAttempts.deleteWhere {
                (FailedLoginAttempts.identifier eq identifier) and
                (FailedLoginAttempts.attemptedAt less windowStart)
            }

            // Record the new failed attempt with all metadata
            FailedLoginAttempts.insert {
                it[FailedLoginAttempts.identifier] = identifier
                it[FailedLoginAttempts.userId] = userId
                it[FailedLoginAttempts.ipAddress] = ipAddress
                it[attemptedAt] = nowLocal
                it[FailedLoginAttempts.reason] = reason
            }
        }
    }

    override fun shouldLockAccount(userId: UUID): LockAccountResult {
        if (!policy.enabled) return LockAccountResult.NoAction

        return kodexTransaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)
            val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

            // Count failed attempts for this REAL account only
            val accountAttempts = FailedLoginAttempts.selectAll().where {
                (FailedLoginAttempts.userId eq userId) and
                (FailedLoginAttempts.attemptedAt greater windowStart)
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
        kodexTransaction {
            FailedLoginAttempts.deleteWhere { FailedLoginAttempts.identifier eq identifier }
        }
    }

    override fun clearFailedAttemptsForUser(userId: UUID) {
        kodexTransaction {
            FailedLoginAttempts.deleteWhere { FailedLoginAttempts.userId eq userId }
        }
    }

    override fun lockAccount(userId: UUID, lockedUntil: LocalDateTime, reason: String) {
        kodexTransaction {
            val clockNow = CurrentKotlinInstant
            val nowLocal = clockNow.toLocalDateTime(TimeZone.UTC)

            // Insert or update lock record
            AccountLocks.deleteWhere { AccountLocks.userId eq userId }
            AccountLocks.insert {
                it[AccountLocks.userId] = userId
                it[AccountLocks.lockedUntil] = lockedUntil
                it[AccountLocks.reason] = reason
                it[lockedAt] = nowLocal
            }
        }
    }

    override fun unlockAccount(userId: UUID) {
        kodexTransaction {
            AccountLocks.deleteWhere { AccountLocks.userId eq userId }
        }
    }

    override fun isAccountLocked(userId: UUID, currentTime: LocalDateTime): Boolean {
        return kodexTransaction {
            AccountLocks.selectAll().where { AccountLocks.userId eq userId }
                .singleOrNull()
                ?.let { row ->
                    val lockedUntil = row[AccountLocks.lockedUntil]
                    // Locked if no expiry (null) or expiry is in the future
                    lockedUntil == null || lockedUntil > currentTime
                } ?: false
        }
    }
}

internal fun accountLockoutService(
    policy: AccountLockoutPolicy,
    timeZone: TimeZone
): AccountLockoutService =
    DefaultAccountLockoutService(policy, timeZone)
