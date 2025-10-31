package com.mustafadakhel.kodex.lockout

import kotlinx.datetime.LocalDateTime

/**
 * Result of throttling checks (Layer 1 - identifier/IP based).
 */
public sealed class ThrottleResult {
    /** Request can proceed */
    public data object NotThrottled : ThrottleResult()

    /** Request should be throttled */
    public data class Throttled(
        val reason: String,
        val attemptCount: Int
    ) : ThrottleResult()
}

/**
 * Result of account lockout check (Layer 2 - real accounts only).
 */
public sealed class LockAccountResult {
    /** No action needed */
    public data object NoAction : LockAccountResult()

    /** Account should be locked */
    public data class ShouldLock(
        val lockedUntil: LocalDateTime,
        val attemptCount: Int
    ) : LockAccountResult()
}

