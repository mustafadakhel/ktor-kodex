package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.ExtensionConfig
import io.ktor.utils.io.*
import kotlinx.datetime.TimeZone

/**
 * Configuration for the account lockout extension.
 * Provides a type-safe DSL for configuring brute force protection.
 *
 * Example usage:
 * ```kotlin
 * realm("admin") {
 *     accountLockout {
 *         policy = AccountLockoutPolicy.strict()
 *         // Or configure inline:
 *         policy = AccountLockoutPolicy(
 *             maxFailedAttempts = 3,
 *             attemptWindow = 15.minutes,
 *             lockoutDuration = 1.hours
 *         )
 *     }
 * }
 * ```
 */
@KtorDsl
public class AccountLockoutConfig : ExtensionConfig() {

    /**
     * The lockout policy to use.
     * Default: AccountLockoutPolicy.moderate()
     *
     * Available presets:
     * - AccountLockoutPolicy.strict() - 3 attempts, 1 hour lockout
     * - AccountLockoutPolicy.moderate() - 5 attempts, 30 min lockout
     * - AccountLockoutPolicy.lenient() - 10 attempts, 15 min lockout
     * - AccountLockoutPolicy.disabled() - No lockout
     */
    public var policy: AccountLockoutPolicy = AccountLockoutPolicy.moderate()

    override fun build(context: com.mustafadakhel.kodex.extension.ExtensionContext): AccountLockoutExtension {
        val service = accountLockoutService(policy)
        return AccountLockoutExtension(service, context.timeZone)
    }
}
