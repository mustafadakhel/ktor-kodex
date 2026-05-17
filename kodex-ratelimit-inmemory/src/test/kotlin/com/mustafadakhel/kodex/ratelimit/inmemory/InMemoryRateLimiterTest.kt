package com.mustafadakhel.kodex.ratelimit.inmemory

import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive tests for InMemoryRateLimiter.
 */
class InMemoryRateLimiterTest : FunSpec({

    test("should allow requests within limit") {
        val limiter = InMemoryRateLimiter()

        // Limit: 3 requests per 10 seconds
        val result1 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val result2 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val result3 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        result3.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should block requests exceeding limit") {
        val limiter = InMemoryRateLimiter()

        // Limit: 2 requests per 10 seconds
        limiter.checkLimit("user:2", limit = 2, window = 10.seconds)
        limiter.checkLimit("user:2", limit = 2, window = 10.seconds)

        // Third request should be blocked
        val result = limiter.checkLimit("user:2", limit = 2, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should use sliding window correctly") {
        val limiter = InMemoryRateLimiter()

        // Limit: 2 requests per 100ms window
        limiter.checkLimit("user:3", limit = 2, window = 100.milliseconds)
        limiter.checkLimit("user:3", limit = 2, window = 100.milliseconds)

        // Should be rate limited immediately
        val result1 = limiter.checkLimit("user:3", limit = 2, window = 100.milliseconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Wait for window to slide
        delay(110.milliseconds)

        // Should be allowed again after window passes
        val result2 = limiter.checkLimit("user:3", limit = 2, window = 100.milliseconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should isolate rate limits per key") {
        val limiter = InMemoryRateLimiter()

        // Exhaust limit for user:4
        limiter.checkLimit("user:4", limit = 1, window = 10.seconds)
        val result1 = limiter.checkLimit("user:4", limit = 1, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // user:5 should still be allowed
        val result2 = limiter.checkLimit("user:5", limit = 1, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("checkAndReserve should reserve capacity") {
        val limiter = InMemoryRateLimiter()

        // Reserve 2 slots
        val reservation1 = limiter.checkAndReserve("user:6", limit = 2, window = 10.seconds)
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation2 = limiter.checkAndReserve("user:6", limit = 2, window = 10.seconds)
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Third reservation should fail
        val reservation3 = limiter.checkAndReserve("user:6", limit = 2, window = 10.seconds)
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("releaseReservation should free capacity") {
        val limiter = InMemoryRateLimiter()

        // Make 2 reservations
        val reservation1 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation2 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Should be at limit
        val reservation3 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Release first reservation
        limiter.releaseReservation(reservation1.reservationId)

        // Should now be able to reserve again
        val reservation4 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation4.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("cooldown should block requests") {
        val limiter = InMemoryRateLimiter()

        // Make reservation with cooldown
        val reservation1 = limiter.checkAndReserve(
            key = "user:8",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Immediate request should be in cooldown (must use checkAndReserve, not checkLimit)
        val reservation2 = limiter.checkAndReserve(
            key = "user:8",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()

        // Wait for cooldown to expire
        delay(210.milliseconds)

        // Should be allowed after cooldown
        val reservation3 = limiter.checkAndReserve(
            key = "user:8",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("clear should remove rate limit for specific key") {
        val limiter = InMemoryRateLimiter()

        // Exhaust limit
        limiter.checkLimit("user:9", limit = 1, window = 10.seconds)
        val result1 = limiter.checkLimit("user:9", limit = 1, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Clear the rate limit
        limiter.clear("user:9")

        // Should be allowed again
        val result2 = limiter.checkLimit("user:9", limit = 1, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("clearAll should remove all rate limits") {
        val limiter = InMemoryRateLimiter()

        // Exhaust limits for multiple keys
        limiter.checkLimit("user:10", limit = 1, window = 10.seconds)
        limiter.checkLimit("user:11", limit = 1, window = 10.seconds)

        limiter.checkLimit("user:10", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Exceeded>()
        limiter.checkLimit("user:11", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Clear all
        limiter.clearAll()

        // Both should be allowed again
        limiter.checkLimit("user:10", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Allowed>()
        limiter.checkLimit("user:11", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should handle concurrent requests safely") {
        val limiter = InMemoryRateLimiter()

        // Make rapid concurrent requests
        val results = (1..10).map {
            limiter.checkLimit("user:12", limit = 5, window = 10.seconds)
        }

        val allowed = results.count { it is RateLimitResult.Allowed }
        val exceeded = results.count { it is RateLimitResult.Exceeded }

        // Should have exactly 5 allowed and 5 exceeded
        allowed shouldBe 5
        exceeded shouldBe 5
    }

    test("should perform lazy cleanup of old entries") {
        val limiter = InMemoryRateLimiter(
            maxEntries = 10,
            cleanupAge = 100.milliseconds
        )

        // Create entries
        repeat(5) { i ->
            limiter.checkLimit("user:cleanup:$i", limit = 10, window = 10.seconds)
        }

        // Wait for cleanup age
        delay(110.milliseconds)

        // Trigger cleanup by making a new request
        limiter.checkLimit("user:cleanup:trigger", limit = 10, window = 10.seconds)

        // Old entries should have been cleaned up, so new entries can be added
        repeat(10) { i ->
            val result = limiter.checkLimit("user:cleanup:new:$i", limit = 10, window = 10.seconds)
            result.shouldBeInstanceOf<RateLimitResult.Allowed>()
        }
    }

    test("should enforce LRU eviction when maxEntries exceeded") {
        val limiter = InMemoryRateLimiter(
            maxEntries = 3,
            cleanupAge = 1.minutes  // Long cleanup age
        )

        // Fill up to max entries with small delays to ensure distinct lastAccess times
        limiter.checkLimit("user:lru:1", limit = 1, window = 10.seconds)
        delay(10.milliseconds)
        limiter.checkLimit("user:lru:2", limit = 1, window = 10.seconds)
        delay(10.milliseconds)
        limiter.checkLimit("user:lru:3", limit = 1, window = 10.seconds)
        delay(10.milliseconds)

        // Verify we're at capacity
        limiter.size() shouldBe 3

        // Adding 4th entry should trigger LRU eviction of user:lru:1 (oldest)
        limiter.checkLimit("user:lru:4", limit = 1, window = 10.seconds)

        // Verify size stayed at max (eviction occurred)
        limiter.size() shouldBe 3

        // Access user:lru:1 again - should start fresh with count=1 (was evicted)
        val result1 = limiter.checkLimit("user:lru:1", limit = 1, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Second access should exceed limit (count=2 > limit=1)
        val result2 = limiter.checkLimit("user:lru:1", limit = 1, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("releaseReservation with null ID should not throw") {
        val limiter = InMemoryRateLimiter()

        // Should not throw
        limiter.releaseReservation(null)
    }

    test("releaseReservation with unknown ID should not affect other reservations") {
        val limiter = InMemoryRateLimiter()

        val reservation = limiter.checkAndReserve("user:13", limit = 2, window = 10.seconds)
        reservation.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Release with fake ID
        limiter.releaseReservation("fake-reservation-id")

        // Original reservation should still count
        limiter.checkAndReserve("user:13", limit = 2, window = 10.seconds)
        val result = limiter.checkAndReserve("user:13", limit = 2, window = 10.seconds)
        result.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should handle zero limit correctly") {
        val limiter = InMemoryRateLimiter()

        // Zero limit should always exceed
        val result = limiter.checkLimit("user:14", limit = 0, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should handle negative limit correctly") {
        val limiter = InMemoryRateLimiter()

        // Negative limit should always exceed
        val result = limiter.checkLimit("user:15", limit = -1, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }
})
