package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.tokens.security.RateLimiter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes

/**
 * Tests to verify that rate limit reservations are properly released on failure.
 * This is the CRITICAL behavior: failures should not count against rate limits.
 */
class RateLimitReleaseTest : FunSpec({

    context("Rate limit reservation and release") {
        test("CRITICAL: failed operations release reservations and don't count toward limit") {
            val rateLimiter = RateLimiter()
            val limit = 3

            // Reserve 3 slots
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res1.isAllowed() shouldBe true

            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res2.isAllowed() shouldBe true

            val res3 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res3.isAllowed() shouldBe true

            // 4th attempt should be rate limited
            val res4 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res4.isAllowed() shouldBe false

            // Release first 3 reservations (simulating failures)
            rateLimiter.releaseReservation(res1.reservationId)
            rateLimiter.releaseReservation(res2.reservationId)
            rateLimiter.releaseReservation(res3.reservationId)

            // Now we should be able to make 3 more attempts
            val res5 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res5.isAllowed() shouldBe true

            val res6 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res6.isAllowed() shouldBe true

            val res7 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res7.isAllowed() shouldBe true

            // 4th attempt should again be rate limited
            val res8 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res8.isAllowed() shouldBe false
        }

        test("partial failures: 2 success, 1 fail, 2 more success = 4 used, then limited") {
            val rateLimiter = RateLimiter()
            val limit = 4

            // Success 1
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res1.isAllowed() shouldBe true
            // Keep this reservation (success)

            // Success 2
            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res2.isAllowed() shouldBe true
            // Keep this reservation (success)

            // Failure - release
            val res3 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res3.isAllowed() shouldBe true
            rateLimiter.releaseReservation(res3.reservationId)

            // Success 3
            val res4 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res4.isAllowed() shouldBe true
            // Keep this reservation (success)

            // Success 4
            val res5 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res5.isAllowed() shouldBe true
            // Keep this reservation (success)

            // Should be rate limited now (4 successes kept)
            val res6 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res6.isAllowed() shouldBe false
        }

        test("10 failures don't prevent any successes") {
            val rateLimiter = RateLimiter()
            val limit = 3

            // Simulate 10 failed attempts (all released)
            for (i in 1..10) {
                val res = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
                res.isAllowed() shouldBe true
                rateLimiter.releaseReservation(res.reservationId)
            }

            // Should still be able to make 3 successful attempts
            val res1 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res1.isAllowed() shouldBe true

            val res2 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res2.isAllowed() shouldBe true

            val res3 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res3.isAllowed() shouldBe true

            // 4th should be limited
            val res4 = rateLimiter.checkAndReserve("test-key", limit, 15.minutes)
            res4.isAllowed() shouldBe false
        }

        test("releasing null reservationId is safe (no-op)") {
            val rateLimiter = RateLimiter()

            // Should not throw
            rateLimiter.releaseReservation(null)
        }
    }
})
