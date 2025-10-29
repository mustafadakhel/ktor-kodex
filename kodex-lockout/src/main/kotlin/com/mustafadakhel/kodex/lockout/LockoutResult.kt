package com.mustafadakhel.kodex.lockout

import kotlinx.datetime.LocalDateTime

/**
 * Result of checking whether an account is locked out.
 */
public sealed class LockoutResult {
    /**
     * Account is not locked.
     */
    public data object NotLocked : LockoutResult()

    /**
     * Account is currently locked due to too many failed attempts.
     *
     * @property lockedUntil Time when the lockout expires
     * @property reason Human-readable reason for lockout
     * @property failedAttemptCount Number of failed attempts that triggered lockout
     */
    public data class Locked(
        val lockedUntil: LocalDateTime,
        val reason: String,
        val failedAttemptCount: Int
    ) : LockoutResult()
}
