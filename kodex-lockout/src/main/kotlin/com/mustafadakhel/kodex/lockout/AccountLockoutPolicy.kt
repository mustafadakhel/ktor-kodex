package com.mustafadakhel.kodex.lockout

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration policy for account lockout protection.
 * Defines thresholds and durations for failed login attempt tracking.
 *
 * @property maxFailedAttempts Maximum failed attempts before lockout
 * @property attemptWindow Time window for counting failed attempts
 * @property lockoutDuration Duration of account lockout
 * @property enabled Whether lockout protection is enabled
 */
public data class AccountLockoutPolicy(
    val maxFailedAttempts: Int = 5,
    val attemptWindow: Duration = 15.minutes,
    val lockoutDuration: Duration = 30.minutes,
    val enabled: Boolean = true
) {
    init {
        require(maxFailedAttempts > 0) { "Max failed attempts must be positive" }
        require(attemptWindow.isPositive()) { "Attempt window must be positive" }
        require(lockoutDuration.isPositive()) { "Lockout duration must be positive" }
    }

    public companion object {
        public fun strict(): AccountLockoutPolicy = AccountLockoutPolicy(
            maxFailedAttempts = 3,
            attemptWindow = 15.minutes,
            lockoutDuration = 1.hours
        )

        public fun moderate(): AccountLockoutPolicy = AccountLockoutPolicy(
            maxFailedAttempts = 5,
            attemptWindow = 15.minutes,
            lockoutDuration = 30.minutes
        )

        public fun lenient(): AccountLockoutPolicy = AccountLockoutPolicy(
            maxFailedAttempts = 10,
            attemptWindow = 30.minutes,
            lockoutDuration = 15.minutes
        )

        public fun disabled(): AccountLockoutPolicy = AccountLockoutPolicy(
            maxFailedAttempts = Int.MAX_VALUE,
            attemptWindow = 1.minutes,
            lockoutDuration = 1.minutes,
            enabled = false
        )
    }
}
