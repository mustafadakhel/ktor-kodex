package com.mustafadakhel.kodex.ratelimit.inmemory

import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.RateLimitReservation
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * In-memory rate limiter using sliding window algorithm.
 */
public class InMemoryRateLimiter(
    private val maxEntries: Int = 100_000,
    private val cleanupAge: Duration = 1.minutes
) : RateLimiter {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got: $maxEntries" }
        require(maxEntries <= 1_000_000) { "maxEntries should not exceed 1,000,000 for memory safety, got: $maxEntries" }
        require(cleanupAge.isPositive()) { "cleanupAge must be positive, got: $cleanupAge" }
    }

    private val attempts = ConcurrentHashMap<String, AttemptWindow>()
    private val lastCleanup = AtomicLong(System.currentTimeMillis())
    private val keyLocks = ConcurrentHashMap<String, Any>()

    override fun checkLimit(
        key: String,
        limit: Int,
        window: Duration
    ): RateLimitResult {
        val lock = keyLocks.computeIfAbsent(key) { Any() }

        synchronized(lock) {
            performLazyCleanup(window)

            if (attempts.size >= maxEntries && !attempts.containsKey(key)) {
                evictOldestEntries()
            }

            val attemptWindow = attempts.compute(key) { _, existing ->
                val now = Clock.System.now()
                val windowCutoff = now - window

                if (existing == null || existing.windowStart < windowCutoff) {
                    AttemptWindow(now, 1, now, now)
                } else {
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

    override fun checkAndReserve(
        key: String,
        limit: Int,
        window: Duration,
        cooldown: Duration?
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

            val currentCount = if (currentWindow == null || currentWindow.windowStart < windowCutoff) {
                0
            } else {
                currentWindow.count
            }

            if (currentCount >= limit) {
                return RateLimitReservation(
                    result = RateLimitResult.Exceeded("Rate limit exceeded: $limit per $window"),
                    reservationId = null
                )
            }

            val attemptWindow = attempts.compute(key) { _, existing ->
                if (existing == null || existing.windowStart < windowCutoff) {
                    AttemptWindow(now, 1, now, now)
                } else if (existing.count == 0) {
                    existing.copy(count = 1, lastAccess = now, lastAttemptTime = now)
                } else {
                    existing.copy(count = existing.count + 1, lastAccess = now, lastAttemptTime = now)
                }
            }!!

            return RateLimitReservation(
                result = RateLimitResult.Allowed,
                reservationId = "$key:${attemptWindow.count}"
            )
        }
    }

    override fun releaseReservation(reservationId: String?) {
        if (reservationId == null) return

        val key = reservationId.substringBeforeLast(":")
        val lock = keyLocks.computeIfAbsent(key) { Any() }

        synchronized(lock) {
            attempts.compute(key) { _, existing ->
                if (existing == null) {
                    null
                } else if (existing.count <= 1) {
                    existing.copy(count = 0, lastAccess = Clock.System.now())
                } else {
                    existing.copy(count = existing.count - 1, lastAccess = Clock.System.now())
                }
            }
        }
    }

    override fun clear(key: String) {
        attempts.remove(key)
    }

    override fun clearAll() {
        attempts.clear()
    }

    public fun size(): Int = attempts.size

    private fun performLazyCleanup(window: Duration) {
        val now = System.currentTimeMillis()
        val lastCleanupTime = lastCleanup.get()

        if (now - lastCleanupTime < 60_000) {
            return
        }

        if (!lastCleanup.compareAndSet(lastCleanupTime, now)) {
            return
        }

        val clockNow = Clock.System.now()
        val cleanupCutoff = clockNow - window - cleanupAge

        attempts.entries.removeIf { (_, attemptWindow) ->
            attemptWindow.windowStart < cleanupCutoff
        }
    }

    private fun evictOldestEntries() {
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
        val lastAccess: Instant,
        val lastAttemptTime: Instant
    )
}
