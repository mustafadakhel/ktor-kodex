package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * Configure account lockout for brute force protection.
 *
 * Example:
 * ```kotlin
 * realm("admin") {
 *     accountLockout {
 *         policy = AccountLockoutPolicy.strict()
 *         // Or configure inline:
 *         // policy = AccountLockoutPolicy(
 *         //     maxFailedAttempts = 3,
 *         //     attemptWindow = 15.minutes,
 *         //     lockoutDuration = 1.hours
 *         // )
 *     }
 * }
 * ```
 */
public fun RealmConfigScope.accountLockout(block: AccountLockoutConfig.() -> Unit) {
    extension(AccountLockoutConfig(), block)
}
