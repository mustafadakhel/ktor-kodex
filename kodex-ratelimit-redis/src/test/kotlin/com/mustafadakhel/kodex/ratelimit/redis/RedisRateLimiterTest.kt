package com.mustafadakhel.kodex.ratelimit.redis

import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.inmemory.InMemoryRateLimiter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.lettuce.core.RedisClient
import kotlinx.coroutines.delay
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for RedisRateLimiter using Testcontainers.
 */
class RedisRateLimiterTest : FunSpec({

    // Redis container for testing
    val redisContainer = GenericContainer(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)

    lateinit var redisClient: RedisClient
    lateinit var limiter: RedisRateLimiter

    beforeSpec {
        redisContainer.start()
        val redisUrl = "redis://${redisContainer.host}:${redisContainer.getMappedPort(6379)}"
        redisClient = RedisClient.create(redisUrl)
    }

    afterSpec {
        redisClient.shutdown()
        redisContainer.stop()
    }

    beforeTest {
        val connection = redisClient.connect()
        val circuitBreaker = CircuitBreaker(
            failureThreshold = 3,
            timeout = 5.seconds,
            halfOpenAttempts = 2
        )
        limiter = RedisRateLimiter(
            connection = connection,
            keyPrefix = "test:",
            circuitBreaker = circuitBreaker,
            fallbackRateLimiter = InMemoryRateLimiter()
        )
        // Clear all keys before each test
        connection.sync().flushall()
    }

    test("should allow requests within limit") {
        // Limit: 3 requests per 10 seconds
        val result1 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val result2 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val result3 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        result3.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should block requests exceeding limit") {
        // Limit: 2 requests per 10 seconds
        limiter.checkLimit("user:2", limit = 2, window = 10.seconds)
        limiter.checkLimit("user:2", limit = 2, window = 10.seconds)

        // Third request should be blocked
        val result = limiter.checkLimit("user:2", limit = 2, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should use sliding window correctly") {
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
        // Exhaust limit for user:4
        limiter.checkLimit("user:4", limit = 1, window = 10.seconds)
        val result1 = limiter.checkLimit("user:4", limit = 1, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // user:5 should still be allowed
        val result2 = limiter.checkLimit("user:5", limit = 1, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should work across multiple limiter instances (distributed)") {
        val connection1 = redisClient.connect()
        val connection2 = redisClient.connect()

        val limiter1 = RedisRateLimiter(
            connection = connection1,
            keyPrefix = "test:",
            circuitBreaker = CircuitBreaker(5, 5.seconds, 2),
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        val limiter2 = RedisRateLimiter(
            connection = connection2,
            keyPrefix = "test:",
            circuitBreaker = CircuitBreaker(5, 5.seconds, 2),
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        // Use limiter1 to make requests
        limiter1.checkLimit("user:6", limit = 2, window = 10.seconds)
        limiter1.checkLimit("user:6", limit = 2, window = 10.seconds)

        // limiter2 should see the same state and block the third request
        val result = limiter2.checkLimit("user:6", limit = 2, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        connection1.close()
        connection2.close()
    }

    test("checkAndReserve should reserve capacity") {
        // Reserve 2 slots
        val reservation1 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation2 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Third reservation should fail
        val reservation3 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("releaseReservation should free capacity") {
        // Make 2 reservations
        val reservation1 = limiter.checkAndReserve("user:8", limit = 2, window = 10.seconds)
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation2 = limiter.checkAndReserve("user:8", limit = 2, window = 10.seconds)
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Should be at limit
        val reservation3 = limiter.checkAndReserve("user:8", limit = 2, window = 10.seconds)
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Release first reservation
        limiter.releaseReservation(reservation1.reservationId)

        // Wait a bit for Redis to process
        delay(50.milliseconds)

        // Should now be able to reserve again
        val reservation4 = limiter.checkAndReserve("user:8", limit = 2, window = 10.seconds)
        reservation4.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("cooldown should block requests") {
        // Make reservation with cooldown
        val reservation1 = limiter.checkAndReserve(
            key = "user:9",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        // Immediate request should be in cooldown
        val reservation2 = limiter.checkAndReserve(
            key = "user:9",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()

        // Wait for cooldown to expire
        delay(210.milliseconds)

        // Should be allowed after cooldown
        val reservation3 = limiter.checkAndReserve(
            key = "user:9",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("clear should remove rate limit for specific key") {
        // Exhaust limit
        limiter.checkLimit("user:10", limit = 1, window = 10.seconds)
        val result1 = limiter.checkLimit("user:10", limit = 1, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Clear the rate limit
        limiter.clear("user:10")

        // Wait a bit for Redis to process
        delay(50.milliseconds)

        // Should be allowed again
        val result2 = limiter.checkLimit("user:10", limit = 1, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("clearAll should remove all rate limits") {
        // Exhaust limits for multiple keys
        limiter.checkLimit("user:11", limit = 1, window = 10.seconds)
        limiter.checkLimit("user:12", limit = 1, window = 10.seconds)

        limiter.checkLimit("user:11", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Exceeded>()
        limiter.checkLimit("user:12", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Exceeded>()

        // Clear all
        limiter.clearAll()

        // Wait a bit for Redis to process
        delay(50.milliseconds)

        // Both should be allowed again
        limiter.checkLimit("user:11", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Allowed>()
        limiter.checkLimit("user:12", limit = 1, window = 10.seconds).shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("circuit breaker should open on failures") {
        val connection = redisClient.connect()
        val circuitBreaker = CircuitBreaker(
            failureThreshold = 2,  // Low threshold for testing
            timeout = 1.seconds,
            halfOpenAttempts = 1
        )
        val testLimiter = RedisRateLimiter(
            connection = connection,
            keyPrefix = "test:",
            circuitBreaker = circuitBreaker,
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        // Make some successful requests first
        testLimiter.checkLimit("user:13", limit = 5, window = 10.seconds)

        // Close the connection to simulate Redis failure
        connection.close()

        // These should trigger circuit breaker and use fallback
        val result1 = testLimiter.checkLimit("user:13", limit = 5, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Allowed>() // Fallback allows

        val result2 = testLimiter.checkLimit("user:13", limit = 5, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>() // Fallback allows

        // Circuit breaker should now be open
        circuitBreaker.isOpen shouldBe true
    }

    test("circuit breaker should recover after timeout") {
        val connection = redisClient.connect()
        val circuitBreaker = CircuitBreaker(
            failureThreshold = 2,
            timeout = 200.milliseconds,  // Short timeout for testing
            halfOpenAttempts = 1
        )
        val testLimiter = RedisRateLimiter(
            connection = connection,
            keyPrefix = "test:",
            circuitBreaker = circuitBreaker,
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        // Close connection to trigger failures
        connection.close()

        // Trigger circuit breaker open
        testLimiter.checkLimit("user:14", limit = 5, window = 10.seconds)
        testLimiter.checkLimit("user:14", limit = 5, window = 10.seconds)

        circuitBreaker.isOpen shouldBe true

        // Wait for timeout
        delay(250.milliseconds)

        // Create a new connection
        val newConnection = redisClient.connect()
        val newLimiter = RedisRateLimiter(
            connection = newConnection,
            keyPrefix = "test:",
            circuitBreaker = circuitBreaker,
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        // Should attempt to use Redis again (half-open state)
        newLimiter.checkLimit("user:14", limit = 5, window = 10.seconds)

        // After successful request, circuit should close
        circuitBreaker.isOpen shouldBe false

        newConnection.close()
    }

    test("should handle concurrent requests safely") {
        // Make rapid concurrent requests
        val results = (1..10).map {
            limiter.checkLimit("user:15", limit = 5, window = 10.seconds)
        }

        val allowed = results.count { it is RateLimitResult.Allowed }
        val exceeded = results.count { it is RateLimitResult.Exceeded }

        // Should have exactly 5 allowed and 5 exceeded
        allowed shouldBe 5
        exceeded shouldBe 5
    }

    test("should apply key prefix correctly") {
        val connection = redisClient.connect()
        val prefixedLimiter = RedisRateLimiter(
            connection = connection,
            keyPrefix = "myapp:limits:",
            circuitBreaker = CircuitBreaker(5, 5.seconds, 2),
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        // Make a request
        prefixedLimiter.checkLimit("user:16", limit = 5, window = 10.seconds)

        // Check that Redis key has the prefix
        val keys = connection.sync().keys("myapp:limits:*")
        keys.size shouldBeGreaterThan 0
        keys.first() shouldBe "myapp:limits:user:16"

        connection.close()
    }

    test("releaseReservation with null ID should not throw") {
        // Should not throw
        limiter.releaseReservation(null)
    }

    test("should handle zero limit correctly") {
        // Zero limit should always exceed
        val result = limiter.checkLimit("user:17", limit = 0, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should handle negative limit correctly") {
        // Negative limit should always exceed
        val result = limiter.checkLimit("user:18", limit = -1, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }
})
