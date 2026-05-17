package com.mustafadakhel.kodex.lockout

import kotlinx.datetime.LocalDateTime

/**
 * Result of throttling checks (Layer 1 - identifier/IP based).
 */
public sealed class ThrottleResult {
    public data object NotThrottled : ThrottleResult()

    public data class Throttled(
        val reason: String,
        val attemptCount: Int
    ) : ThrottleResult()
}

/**
 * Result of account lockout check (Layer 2 - real accounts only).
 */
public sealed class LockAccountResult {
    public data object NoAction : LockAccountResult()

    public data class ShouldLock(
        val lockedUntil: LocalDateTime,
        val attemptCount: Int
    ) : LockAccountResult()
}

