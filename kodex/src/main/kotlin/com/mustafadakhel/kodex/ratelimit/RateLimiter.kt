package com.mustafadakhel.kodex.ratelimit

import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Rate limiter interface for controlling request frequency.
 *
 * Implementations can be in-memory, Redis-backed, or any other storage mechanism.
 *
 * All methods are suspend functions to support non-blocking I/O operations.
 */
public interface RateLimiter {
    public suspend fun checkLimit(
        key: String,
        limit: Int,
        window: Duration
    ): RateLimitResult

    public suspend fun checkAndReserve(
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
    public suspend fun releaseReservation(reservationId: String?)

    public suspend fun clear(key: String)

    public suspend fun clearAll()
}

public sealed interface RateLimitResult {
    public data object Allowed : RateLimitResult
    public data class Exceeded(val reason: String) : RateLimitResult
    public data class Cooldown(
        val reason: String,
        val retryAfter: Instant? = null
    ) : RateLimitResult
}

public data class RateLimitReservation(
    val result: RateLimitResult,
    val reservationId: String?
) {
    public fun isAllowed(): Boolean = result is RateLimitResult.Allowed
}

public class NoOpRateLimiter : RateLimiter {
    override suspend fun checkLimit(key: String, limit: Int, window: Duration): RateLimitResult =
        RateLimitResult.Allowed

    override suspend fun checkAndReserve(
        key: String,
        limit: Int,
        window: Duration,
        cooldown: Duration?
    ): RateLimitReservation = RateLimitReservation(RateLimitResult.Allowed, null)

    override suspend fun releaseReservation(reservationId: String?) {}

    override suspend fun clear(key: String) {}

    override suspend fun clearAll() {}
}
