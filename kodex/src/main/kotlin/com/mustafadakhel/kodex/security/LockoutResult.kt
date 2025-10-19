package com.mustafadakhel.kodex.security

import kotlinx.datetime.LocalDateTime

public sealed class LockoutResult {
    public data object NotLocked : LockoutResult()

    public data class Locked(
        val lockedUntil: LocalDateTime,
        val reason: String,
        val failedAttemptCount: Int
    ) : LockoutResult()
}

public sealed class LockoutCheckResult {
    public data object Allowed : LockoutCheckResult()

    public data class AccountLocked(
        val lockedUntil: LocalDateTime,
        val remainingAttempts: Int = 0
    ) : LockoutCheckResult()

    public data class RateLimited(
        val remainingAttempts: Int,
        val resetAt: LocalDateTime
    ) : LockoutCheckResult()
}
