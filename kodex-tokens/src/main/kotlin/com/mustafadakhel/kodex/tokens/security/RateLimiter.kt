package com.mustafadakhel.kodex.tokens.security

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory rate limiter using sliding window algorithm with automatic cleanup.
 *
 * SECURITY NOTE: This implementation includes:
 * - Memory leak prevention (lazy cleanup, max size, LRU eviction)
 * - Race condition prevention (per-key locking for atomic check-and-increment)
 * - Two-phase operations (checkAndReserve/release for rollback on failure)
 */
public class RateLimiter(
    /**
     * Maximum number of entries to keep in memory.
     * Prevents unbounded growth from unique key generation attacks.
     * Default: 100,000 entries
     */
    private val maxEntries: Int = 100_000,

    /**
     * How long to keep expired windows before cleanup.
     * Expired windows older than this are removed during operations.
     * Default: 1 hour
     */
    private val cleanupAge: Duration = 1.minutes
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got: $maxEntries" }
        require(maxEntries <= 1_000_000) { "maxEntries should not exceed 1,000,000 for memory safety, got: $maxEntries" }
        require(cleanupAge.isPositive()) { "cleanupAge must be positive, got: $cleanupAge" }
    }
    private val attempts = ConcurrentHashMap<String, AttemptWindow>()
    private val lastCleanup = AtomicLong(System.currentTimeMillis())

    // Per-key locks to prevent race conditions
    private val keyLocks = ConcurrentHashMap<String, Any>()

    /**
     * Checks if a rate limit has been exceeded for the given key.
     *
     * LEGACY METHOD: Prefer checkAndReserve() for operations that might fail.
     * This method immediately increments the counter, which cannot be rolled back.
     *
     * @param key Unique identifier for rate limiting (userId, email, IP, etc.)
     * @param limit Maximum number of attempts allowed in the window
     * @param window Time window for rate limiting
     * @return RateLimitResult indicating if the limit was exceeded
     */
    public fun checkLimit(
        key: String,
        limit: Int,
        window: Duration
    ): RateLimitResult {
        val lock = keyLocks.computeIfAbsent(key) { Any() }

        // Synchronize on the specific key to prevent race conditions
        synchronized(lock) {
            // Lazy cleanup: Remove expired entries periodically
            performLazyCleanup(window)

            // Enforce maximum size before adding new entry
            if (attempts.size >= maxEntries && !attempts.containsKey(key)) {
                // Map is full and this is a new key - enforce limit
                evictOldestEntries()
            }

            val attemptWindow = attempts.compute(key) { _, existing ->
                val now = Clock.System.now()
                val windowCutoff = now - window

                if (existing == null || existing.windowStart < windowCutoff) {
                    // Start new window - windowStart is now (first attempt timestamp)
                    AttemptWindow(now, 1, now, now)
                } else {
                    // Increment existing window
                    existing.copy(count = existing.count + 1, lastAccess = now, lastAttemptTime = now)
                }
            }!!

            return if (attemptWindow.count > limit) {
                RateLimitResult.Exceeded("Rate limit exceeded: $limit per $window")
            } else {
                RateLimitResult.Allowed
            }
        }
    }

    /**
     * Two-phase rate limiting: Check and reserve a slot atomically.
     * If the operation fails, call releaseReservation() to return the slot.
     * If the operation succeeds, the reservation is automatically kept.
     *
     * RECOMMENDED for operations that might fail (email send, SMS send, etc.)
     *
     * @param key Unique identifier for rate limiting
     * @param limit Maximum number of attempts allowed in the window
     * @param window Time window for rate limiting
     * @param cooldown Optional minimum time between requests (prevents spam)
     * @return RateLimitReservation with result and reservation ID
     */
    public fun checkAndReserve(
        key: String,
        limit: Int,
        window: Duration,
        cooldown: Duration? = null
    ): RateLimitReservation {
        val lock = keyLocks.computeIfAbsent(key) { Any() }

        synchronized(lock) {
            performLazyCleanup(window)

            if (attempts.size >= maxEntries && !attempts.containsKey(key)) {
                evictOldestEntries()
            }

            val now = Clock.System.now()
            val windowCutoff = now - window
            val currentWindow = attempts[key]

            // Check cooldown first (if provided and window exists)
            if (cooldown != null && currentWindow != null && currentWindow.windowStart >= windowCutoff) {
                val timeSinceLastAttempt = now - currentWindow.lastAttemptTime
                if (timeSinceLastAttempt < cooldown) {
                    val retryAfter = currentWindow.lastAttemptTime + cooldown
                    return RateLimitReservation(
                        result = RateLimitResult.Cooldown(
                            reason = "Cooldown period not elapsed. Minimum $cooldown between requests.",
                            retryAfter = retryAfter
                        ),
                        reservationId = null
                    )
                }
            }

            // Check current count
            val currentCount = if (currentWindow == null || currentWindow.windowStart < windowCutoff) {
                0
            } else {
                currentWindow.count
            }

            // Check if we would exceed the limit BEFORE incrementing
            if (currentCount >= limit) {
                return RateLimitReservation(
                    result = RateLimitResult.Exceeded("Rate limit exceeded: $limit per $window"),
                    reservationId = null
                )
            }

            // OK to proceed - increment the counter
            val attemptWindow = attempts.compute(key) { _, existing ->
                if (existing == null || existing.windowStart < windowCutoff) {
                    // Start new window
                    AttemptWindow(now, 1, now, now)
                } else if (existing.count == 0) {
                    // Window exists but all reservations were released
                    // Keep windowStart and set count to 1, update lastAttemptTime
                    existing.copy(count = 1, lastAccess = now, lastAttemptTime = now)
                } else {
                    // Normal increment
                    existing.copy(count = existing.count + 1, lastAccess = now, lastAttemptTime = now)
                }
            }!!

            return RateLimitReservation(
                result = RateLimitResult.Allowed,
                reservationId = "$key:${attemptWindow.count}"
            )
        }
    }

    /**
     * Releases a reservation if the operation failed.
     * Decrements the counter to allow retry.
     *
     * IMPORTANT: Does NOT remove the entry when count reaches 0,
     * because we need to preserve lastAttemptTime for cooldown enforcement.
     *
     * @param reservationId The reservation ID from checkAndReserve()
     */
    public fun releaseReservation(reservationId: String?) {
        if (reservationId == null) return

        val key = reservationId.substringBeforeLast(":")
        val lock = keyLocks.computeIfAbsent(key) { Any() }

        synchronized(lock) {
            attempts.compute(key) { _, existing ->
                if (existing == null) {
                    null // Nothing to release
                } else if (existing.count <= 1) {
                    // Keep entry with count=0 to preserve lastAttemptTime for cooldown
                    existing.copy(count = 0, lastAccess = Clock.System.now())
                } else {
                    existing.copy(count = existing.count - 1, lastAccess = Clock.System.now())
                }
            }
        }
    }

    /**
     * Clears all rate limit data for a specific key.
     *
     * @param key The key to clear
     */
    public fun clear(key: String) {
        attempts.remove(key)
    }

    /**
     * Clears all rate limit data.
     */
    public fun clearAll() {
        attempts.clear()
    }

    /**
     * Returns current number of tracked rate limit entries.
     * Useful for monitoring memory usage.
     */
    public fun size(): Int = attempts.size

    /**
     * Performs lazy cleanup of expired entries.
     * Only runs if enough time has passed since last cleanup.
     */
    private fun performLazyCleanup(window: Duration) {
        val now = System.currentTimeMillis()
        val lastCleanupTime = lastCleanup.get()

        // Only cleanup once per minute to avoid overhead
        if (now - lastCleanupTime < 60_000) {
            return
        }

        // Try to acquire cleanup lock
        if (!lastCleanup.compareAndSet(lastCleanupTime, now)) {
            return // Another thread is cleaning
        }

        val clockNow = Clock.System.now()
        val cleanupCutoff = clockNow - window - cleanupAge

        // Remove all entries with windows that ended long ago
        attempts.entries.removeIf { (_, attemptWindow) ->
            attemptWindow.windowStart < cleanupCutoff
        }
    }

    /**
     * Evicts oldest entries when map reaches maximum size.
     * Uses LRU (Least Recently Used) strategy based on lastAccess time.
     */
    private fun evictOldestEntries() {
        // Remove oldest 10% of entries
        val entriesToRemove = maxOf(1, maxEntries / 10)

        val sortedByAge = attempts.entries
            .sortedBy { it.value.lastAccess }
            .take(entriesToRemove)

        sortedByAge.forEach { entry ->
            attempts.remove(entry.key, entry.value)
        }
    }

    private data class AttemptWindow(
        val windowStart: Instant,
        val count: Int,
        val lastAccess: Instant, // Track for LRU eviction
        val lastAttemptTime: Instant // Track for cooldown enforcement
    )
}

/**
 * Result of a rate limit check.
 */
public sealed interface RateLimitResult {
    /**
     * Request is allowed (under rate limit).
     */
    public data object Allowed : RateLimitResult

    /**
     * Request exceeded rate limit.
     *
     * @property reason Human-readable reason for limit being exceeded
     */
    public data class Exceeded(val reason: String) : RateLimitResult

    /**
     * Request rejected due to cooldown period not elapsed.
     * User must wait before making another request.
     *
     * @property reason Human-readable reason for cooldown
     * @property retryAfter When the user can retry (null if not calculable)
     */
    public data class Cooldown(
        val reason: String,
        val retryAfter: Instant? = null
    ) : RateLimitResult
}

/**
 * Result of checkAndReserve() operation.
 * Contains both the rate limit result and a reservation ID for rollback.
 */
public data class RateLimitReservation(
    val result: RateLimitResult,
    val reservationId: String?
) {
    /**
     * True if the request is allowed (under rate limit).
     */
    public fun isAllowed(): Boolean = result is RateLimitResult.Allowed
}
