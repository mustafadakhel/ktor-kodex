package com.mustafadakhel.kodex.ratelimit

import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Rate limiter interface for controlling request frequency.
 *
 * Implementations can be in-memory, Redis-backed, or any other storage mechanism.
 */
public interface RateLimiter {
    public fun checkLimit(
        key: String,
        limit: Int,
        window: Duration
    ): RateLimitResult

    public fun checkAndReserve(
        key: String,
        limit: Int,
        window: Duration,
        cooldown: Duration? = null
    ): RateLimitReservation

    /**
     * Release a previously reserved slot.
     * Used to rollback a reservation if the operation fails.
     *
     * @param reservationId The reservation ID returned from checkAndReserve
     */
    public fun releaseReservation(reservationId: String?)

    /**
     * Clear rate limit for a specific key.
     *
     * @param key The key to clear
     */
    public fun clear(key: String)

    /**
     * Clear all rate limits.
     */
    public fun clearAll()
}

/**
 * Result of a rate limit check.
 */
public sealed interface RateLimitResult {
    /**
     * Request is allowed.
     */
    public data object Allowed : RateLimitResult

    /**
     * Request exceeded the rate limit.
     *
     * @param reason Human-readable reason for the limit
     */
    public data class Exceeded(val reason: String) : RateLimitResult

    /**
     * Request is in cooldown period.
     *
     * @param reason Human-readable reason for the cooldown
     * @param retryAfter When the cooldown expires
     */
    public data class Cooldown(
        val reason: String,
        val retryAfter: Instant? = null
    ) : RateLimitResult
}

/**
 * Result of a rate limit reservation.
 *
 * @param result The rate limit result
 * @param reservationId Optional ID to release the reservation if needed
 */
public data class RateLimitReservation(
    val result: RateLimitResult,
    val reservationId: String?
) {
    public fun isAllowed(): Boolean = result is RateLimitResult.Allowed
}

public class NoOpRateLimiter : RateLimiter {
    override fun checkLimit(key: String, limit: Int, window: Duration): RateLimitResult =
        RateLimitResult.Allowed

    override fun checkAndReserve(
        key: String,
        limit: Int,
        window: Duration,
        cooldown: Duration?
    ): RateLimitReservation = RateLimitReservation(RateLimitResult.Allowed, null)

    override fun releaseReservation(reservationId: String?) {}

    override fun clear(key: String) {}

    override fun clearAll() {}
}
