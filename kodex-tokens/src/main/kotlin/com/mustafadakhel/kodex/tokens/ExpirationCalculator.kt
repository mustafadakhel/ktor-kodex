package com.mustafadakhel.kodex.tokens

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Calculates token expiration timestamps.
 *
 * Provides consistent expiration calculation across core and extension modules.
 * Used for JWT tokens, verification codes, password reset tokens, and any other
 * time-bounded tokens.
 *
 * Example usage:
 * ```kotlin
 * // With Duration
 * val expiresAt = ExpirationCalculator.calculateExpiration(24.hours, timeZone)
 *
 * // With milliseconds
 * val expiresAt = ExpirationCalculator.calculateExpiration(86400000L, timeZone)
 * ```
 */
public object ExpirationCalculator {
    /**
     * Calculate expiration timestamp from current time + duration.
     *
     * @param duration How long the token is valid
     * @param timeZone TimeZone for LocalDateTime conversion (default: UTC)
     * @param now Current instant (default: Clock.System.now() - injectable for testing)
     * @return LocalDateTime when token expires
     */
    public fun calculateExpiration(
        duration: Duration,
        timeZone: TimeZone = TimeZone.UTC,
        now: Instant = Clock.System.now()
    ): LocalDateTime {
        return (now + duration).toLocalDateTime(timeZone)
    }

    /**
     * Calculate expiration timestamp from current time + duration in milliseconds.
     *
     * @param durationMs How long the token is valid in milliseconds
     * @param timeZone TimeZone for LocalDateTime conversion (default: UTC)
     * @param now Current instant (default: Clock.System.now() - injectable for testing)
     * @return LocalDateTime when token expires
     */
    public fun calculateExpiration(
        durationMs: Long,
        timeZone: TimeZone = TimeZone.UTC,
        now: Instant = Clock.System.now()
    ): LocalDateTime {
        return (now + durationMs.milliseconds).toLocalDateTime(timeZone)
    }
}
