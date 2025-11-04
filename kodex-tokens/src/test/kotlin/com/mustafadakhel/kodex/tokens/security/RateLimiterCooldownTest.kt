package com.mustafadakhel.kodex.tokens.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests to verify that cooldown periods work correctly.
 * CRITICAL: Prevents users from spamming requests (e.g., 5 emails in 1 second).
 */
class RateLimiterCooldownTest : FunSpec({

    context("Cooldown enforcement") {
        test("CRITICAL: requests within cooldown period are rejected") {
            val rateLimiter = RateLimiter()
            val limit = 5
            val cooldown = 2.seconds

            // First request - should be allowed
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res1.isAllowed() shouldBe true

            // Immediate second request - should be rejected (cooldown not elapsed)
            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res2.isAllowed() shouldBe false
            res2.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()

            val cooldownResult = res2.result as RateLimitResult.Cooldown
            cooldownResult.reason shouldBe "Cooldown period not elapsed. Minimum $cooldown between requests."
            cooldownResult.retryAfter shouldNotBe null // Verify retryAfter is set
        }

        test("requests after cooldown period are allowed") {
            val rateLimiter = RateLimiter()
            val limit = 5
            val cooldown = 100.milliseconds

            // First request
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res1.isAllowed() shouldBe true

            // Wait for cooldown to elapse
            delay(110) // 110ms > 100ms cooldown

            // Second request - should be allowed now
            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res2.isAllowed() shouldBe true
        }

        test("cooldown is enforced per key") {
            val rateLimiter = RateLimiter()
            val limit = 5
            val cooldown = 2.seconds

            // Request for key1
            val res1 = rateLimiter.checkAndReserve("key1", limit, 15.minutes, cooldown)
            res1.isAllowed() shouldBe true

            // Immediate request for key2 - should be allowed (different key)
            val res2 = rateLimiter.checkAndReserve("key2", limit, 15.minutes, cooldown)
            res2.isAllowed() shouldBe true

            // Immediate second request for key1 - should be rejected (cooldown on key1)
            val res3 = rateLimiter.checkAndReserve("key1", limit, 15.minutes, cooldown)
            res3.isAllowed() shouldBe false
            res3.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()
        }

        test("no cooldown parameter means no cooldown enforcement") {
            val rateLimiter = RateLimiter()
            val limit = 5

            // First request
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res1.isAllowed() shouldBe true

            // Immediate second request - should be allowed (no cooldown)
            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res2.isAllowed() shouldBe true

            // All 5 requests should be allowed immediately
            val res3 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res3.isAllowed() shouldBe true

            val res4 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res4.isAllowed() shouldBe true

            val res5 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res5.isAllowed() shouldBe true

            // 6th should hit rate limit (not cooldown)
            val res6 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res6.isAllowed() shouldBe false
            res6.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
        }

        test("cooldown is checked before rate limit") {
            val rateLimiter = RateLimiter()
            val limit = 2
            val cooldown = 2.seconds

            // Use up the rate limit (2 requests)
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res1.isAllowed() shouldBe true

            // Wait for cooldown to elapse
            delay(2100) // 2.1s > 2s cooldown

            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res2.isAllowed() shouldBe true

            // Immediate 3rd request - should hit cooldown (not rate limit)
            val res3 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res3.isAllowed() shouldBe false
            res3.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()
        }

        test("released reservations don't reset cooldown timer") {
            val rateLimiter = RateLimiter()
            val limit = 3
            val cooldown = 2.seconds

            // First request
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res1.isAllowed() shouldBe true

            // Immediate second request - should hit cooldown
            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res2.isAllowed() shouldBe false
            res2.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()

            // Release the first reservation (simulating failure)
            rateLimiter.releaseReservation(res1.reservationId)

            // Immediate third request - should STILL hit cooldown
            // (cooldown tracks last ATTEMPT time, not last SUCCESS time)
            val res3 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes, cooldown)
            res3.isAllowed() shouldBe false
            res3.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()
        }

        test("cooldown prevents spam: 5 requests in 1 second scenario") {
            val rateLimiter = RateLimiter()
            val limit = 5
            val cooldown = 1.seconds

            // First request - allowed
            val res1 = rateLimiter.checkAndReserve("user:123", limit, 15.minutes, cooldown)
            res1.isAllowed() shouldBe true

            // Try 4 more immediate requests - all should hit cooldown
            repeat(4) {
                val res = rateLimiter.checkAndReserve("user:123", limit, 15.minutes, cooldown)
                res.isAllowed() shouldBe false
                res.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()
            }

            // Wait for cooldown to elapse
            delay(1100) // 1.1s > 1s cooldown

            // Now second request should be allowed
            val res2 = rateLimiter.checkAndReserve("user:123", limit, 15.minutes, cooldown)
            res2.isAllowed() shouldBe true
        }
    }
})
