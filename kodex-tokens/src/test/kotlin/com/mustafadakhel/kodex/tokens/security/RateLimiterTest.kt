package com.mustafadakhel.kodex.tokens.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest : StringSpec({

    "checkLimit should allow first attempt" {
        val limiter = RateLimiter()
        val result = limiter.checkLimit(
            key = "user:123",
            limit = 5,
            window = 1.minutes
        )

        result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    "checkLimit should allow attempts under limit" {
        val limiter = RateLimiter()

        repeat(5) {
            val result = limiter.checkLimit(
                key = "user:123",
                limit = 5,
                window = 1.minutes
            )
            result.shouldBeInstanceOf<RateLimitResult.Allowed>()
        }
    }

    "checkLimit should exceed when over limit" {
        val limiter = RateLimiter()

        // Make 5 attempts (allowed)
        repeat(5) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
        }

        // 6th attempt should be exceeded
        val result = limiter.checkLimit(
            key = "user:123",
            limit = 5,
            window = 1.minutes
        )

        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
        (result as RateLimitResult.Exceeded).reason shouldBe "Rate limit exceeded: 5 per 1m"
    }

    "checkLimit should track separate keys independently" {
        val limiter = RateLimiter()

        // Max out user:123 (6 attempts to exceed limit of 5)
        repeat(6) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
        }

        // Verify user:123 is exceeded
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // user:456 should still be allowed
        limiter.checkLimit(key = "user:456", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    "checkLimit should reset window after expiration" {
        val limiter = RateLimiter()

        // Make attempts with very short window
        repeat(3) {
            limiter.checkLimit(key = "user:123", limit = 3, window = 10.seconds)
        }

        // Should be at limit
        limiter.checkLimit(key = "user:123", limit = 3, window = 10.seconds)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Wait for window to expire
        Thread.sleep(11000)

        // Should allow again (new window)
        limiter.checkLimit(key = "user:123", limit = 3, window = 10.seconds)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    "checkLimit should start new window when previous expired" {
        val limiter = RateLimiter()

        // Make 2 attempts with short window
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.seconds)
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.seconds)

        // Wait for window to expire
        Thread.sleep(1100)

        // Next attempt should start fresh window at count 1
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.seconds)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Make 4 more attempts (total 5 in new window)
        repeat(4) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.seconds)
                .shouldBeInstanceOf<RateLimitResult.Allowed>()
        }

        // 6th in new window should exceed
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.seconds)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    "clear should remove rate limit for specific key" {
        val limiter = RateLimiter()

        // Max out user:123
        repeat(5) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
        }
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Clear the rate limit
        limiter.clear("user:123")

        // Should be allowed again
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    "clear should not affect other keys" {
        val limiter = RateLimiter()

        // Max out user:123
        repeat(6) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
        }

        // Max out user:456
        repeat(6) {
            limiter.checkLimit(key = "user:456", limit = 5, window = 1.minutes)
        }

        // Clear only user:123
        limiter.clear("user:123")

        // user:123 should be allowed (cleared), user:456 still exceeded
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()

        // user:456 still exceeded (not cleared)
        limiter.checkLimit(key = "user:456", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    "clearAll should remove all rate limits" {
        val limiter = RateLimiter()

        // Max out multiple users
        repeat(6) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
        }
        repeat(6) {
            limiter.checkLimit(key = "user:456", limit = 5, window = 1.minutes)
        }
        repeat(6) {
            limiter.checkLimit(key = "user:789", limit = 5, window = 1.minutes)
        }

        // Clear all
        limiter.clearAll()

        // All should be allowed again
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
        limiter.checkLimit(key = "user:456", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
        limiter.checkLimit(key = "user:789", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    "checkLimit should handle limit of 1" {
        val limiter = RateLimiter()

        limiter.checkLimit(key = "user:123", limit = 1, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()

        limiter.checkLimit(key = "user:123", limit = 1, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    "checkLimit should handle zero limit" {
        val limiter = RateLimiter()

        // Even first attempt should exceed with limit of 0
        limiter.checkLimit(key = "user:123", limit = 0, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    "checkLimit should use sliding window algorithm" {
        val limiter = RateLimiter()

        // Make 3 attempts
        repeat(3) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
                .shouldBeInstanceOf<RateLimitResult.Allowed>()
        }

        // Make 2 more attempts (total 5, at limit)
        repeat(2) {
            limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
                .shouldBeInstanceOf<RateLimitResult.Allowed>()
        }

        // 6th attempt should exceed
        limiter.checkLimit(key = "user:123", limit = 5, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    "checkLimit should handle different window sizes" {
        val limiter = RateLimiter()

        // With 1 hour window
        limiter.checkLimit(key = "user:123", limit = 100, window = 1.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()

        // With 5 second window
        limiter.checkLimit(key = "user:456", limit = 3, window = 5.seconds)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()

        // With 1 day window
        limiter.checkLimit(key = "user:789", limit = 1000, window = 24.minutes)
            .shouldBeInstanceOf<RateLimitResult.Allowed>()
    }
})
