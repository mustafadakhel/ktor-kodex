package com.mustafadakhel.kodex.ratelimit.redis

import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.inmemory.InMemoryRateLimiter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.RedisClusterClient
import kotlinx.coroutines.delay
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedisClusterRateLimiterTest : FunSpec({

    lateinit var clusterContainer: GenericContainer<*>
    lateinit var clusterClient: RedisClusterClient
    lateinit var limiter: RedisClusterRateLimiter

    beforeSpec {
        clusterContainer = GenericContainer(DockerImageName.parse("grokzen/redis-cluster:7.0.10"))
            .withExposedPorts(7000, 7001, 7002, 7003, 7004, 7005)
            .withEnv("IP", "0.0.0.0")
            .withEnv("INITIAL_PORT", "7000")

        clusterContainer.start()

        val host = clusterContainer.host
        val port = clusterContainer.getMappedPort(7000)

        clusterClient = RedisClusterClient.create(RedisURI.create(host, port))

        val circuitBreaker = CircuitBreaker(
            failureThreshold = 3,
            timeout = 5.seconds,
            halfOpenAttempts = 2
        )

        limiter = RedisClusterRateLimiter(
            connection = clusterClient.connect(),
            keyPrefix = "test:ratelimit:",
            circuitBreaker = circuitBreaker,
            fallbackRateLimiter = InMemoryRateLimiter()
        )
    }

    afterSpec {
        limiter.close()
        clusterClient.shutdown()
        clusterContainer.stop()
    }

    beforeTest {
        limiter.clearAll()
    }

    test("should allow requests within limit") {
        val result1 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        val result2 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)
        val result3 = limiter.checkLimit("user:1", limit = 3, window = 10.seconds)

        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
        result3.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should block requests exceeding limit") {
        repeat(3) {
            limiter.checkLimit("user:2", limit = 3, window = 10.seconds)
        }

        val exceededResult = limiter.checkLimit("user:2", limit = 3, window = 10.seconds)
        exceededResult.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should use sliding window correctly") {
        limiter.checkLimit("user:3", limit = 2, window = 500.milliseconds)
        limiter.checkLimit("user:3", limit = 2, window = 500.milliseconds)

        val result1 = limiter.checkLimit("user:3", limit = 2, window = 500.milliseconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        delay(600.milliseconds)

        val result2 = limiter.checkLimit("user:3", limit = 2, window = 500.milliseconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should handle multiple keys independently") {
        repeat(3) {
            limiter.checkLimit("user:4", limit = 3, window = 10.seconds)
        }

        val result1 = limiter.checkLimit("user:4", limit = 3, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        val result2 = limiter.checkLimit("user:5", limit = 3, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("checkAndReserve should reserve slot") {
        val reservation1 = limiter.checkAndReserve("user:6", limit = 3, window = 10.seconds)
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
        reservation1.reservationId shouldBe "test:ratelimit:user:6:${reservation1.reservationId!!.substringAfterLast(":")}"

        val reservation2 = limiter.checkAndReserve("user:6", limit = 3, window = 10.seconds)
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation3 = limiter.checkAndReserve("user:6", limit = 3, window = 10.seconds)
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation4 = limiter.checkAndReserve("user:6", limit = 3, window = 10.seconds)
        reservation4.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
        reservation4.reservationId shouldBe null
    }

    test("releaseReservation should free slot") {
        val reservation1 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        val reservation2 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)

        val reservation3 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        limiter.releaseReservation(reservation1.reservationId)

        val reservation4 = limiter.checkAndReserve("user:7", limit = 2, window = 10.seconds)
        reservation4.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("cooldown should block requests") {
        val reservation1 = limiter.checkAndReserve(
            key = "user:8",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation1.result.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val reservation2 = limiter.checkAndReserve(
            key = "user:8",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation2.result.shouldBeInstanceOf<RateLimitResult.Cooldown>()

        delay(250.milliseconds)

        val reservation3 = limiter.checkAndReserve(
            key = "user:8",
            limit = 5,
            window = 10.seconds,
            cooldown = 200.milliseconds
        )
        reservation3.result.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("clear should remove key") {
        repeat(2) {
            limiter.checkLimit("user:9", limit = 3, window = 10.seconds)
        }

        val result1 = limiter.checkLimit("user:9", limit = 3, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()

        limiter.clear("user:9")

        repeat(3) {
            val result = limiter.checkLimit("user:9", limit = 3, window = 10.seconds)
            result.shouldBeInstanceOf<RateLimitResult.Allowed>()
        }
    }

    test("clearAll should remove all keys") {
        repeat(2) {
            limiter.checkLimit("user:10", limit = 3, window = 10.seconds)
            limiter.checkLimit("user:11", limit = 3, window = 10.seconds)
            limiter.checkLimit("user:12", limit = 3, window = 10.seconds)
        }

        limiter.clearAll()

        val result1 = limiter.checkLimit("user:10", limit = 3, window = 10.seconds)
        val result2 = limiter.checkLimit("user:11", limit = 3, window = 10.seconds)
        val result3 = limiter.checkLimit("user:12", limit = 3, window = 10.seconds)

        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()
        result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
        result3.shouldBeInstanceOf<RateLimitResult.Allowed>()
    }

    test("should work with distributed instances - same key") {
        val limiter2 = RedisClusterRateLimiter(
            connection = clusterClient.connect(),
            keyPrefix = "test:ratelimit:",
            circuitBreaker = CircuitBreaker(3, 5.seconds, 2),
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        try {
            repeat(2) {
                limiter.checkLimit("user:distributed:1", limit = 3, window = 10.seconds)
            }

            limiter2.checkLimit("user:distributed:1", limit = 3, window = 10.seconds)

            val result = limiter2.checkLimit("user:distributed:1", limit = 3, window = 10.seconds)
            result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
        } finally {
            limiter2.close()
        }
    }

    test("should maintain separate limits per key in distributed setup") {
        val limiter2 = RedisClusterRateLimiter(
            connection = clusterClient.connect(),
            keyPrefix = "test:ratelimit:",
            circuitBreaker = CircuitBreaker(3, 5.seconds, 2),
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        try {
            repeat(3) {
                limiter.checkLimit("user:distributed:2", limit = 3, window = 10.seconds)
            }

            val result1 = limiter.checkLimit("user:distributed:2", limit = 3, window = 10.seconds)
            result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

            val result2 = limiter2.checkLimit("user:distributed:3", limit = 3, window = 10.seconds)
            result2.shouldBeInstanceOf<RateLimitResult.Allowed>()
        } finally {
            limiter2.close()
        }
    }

    test("circuit breaker should open after failures") {
        val failingLimiter = RedisClusterRateLimiter(
            connection = clusterClient.connect(),
            keyPrefix = "test:ratelimit:",
            circuitBreaker = CircuitBreaker(
                failureThreshold = 2,
                timeout = 1.seconds,
                halfOpenAttempts = 1
            ),
            fallbackRateLimiter = InMemoryRateLimiter()
        )

        try {
            clusterContainer.stop()

            repeat(3) {
                failingLimiter.checkLimit("user:circuit:1", limit = 3, window = 10.seconds)
            }

            val result = failingLimiter.checkLimit("user:circuit:1", limit = 3, window = 10.seconds)
            result.shouldBeInstanceOf<RateLimitResult.Allowed>()
        } finally {
            failingLimiter.close()
            clusterContainer.start()
        }
    }

    test("should handle high throughput") {
        val key = "user:throughput"
        val limit = 100
        val window = 10.seconds

        val successCount = (1..150).count {
            val result = limiter.checkLimit(key, limit, window)
            result is RateLimitResult.Allowed
        }

        successCount shouldBe limit
    }

    test("should handle concurrent requests") {
        val key = "user:concurrent"
        val limit = 50
        val window = 10.seconds

        val results = (1..100).map {
            limiter.checkLimit(key, limit, window)
        }

        val allowedCount = results.count { it is RateLimitResult.Allowed }
        val exceededCount = results.count { it is RateLimitResult.Exceeded }

        allowedCount shouldBe limit
        exceededCount shouldBe 50
    }

    test("should expire old entries from window") {
        limiter.checkLimit("user:expire", limit = 2, window = 300.milliseconds)
        limiter.checkLimit("user:expire", limit = 2, window = 300.milliseconds)

        val result1 = limiter.checkLimit("user:expire", limit = 2, window = 300.milliseconds)
        result1.shouldBeInstanceOf<RateLimitResult.Exceeded>()

        delay(400.milliseconds)

        repeat(2) {
            val result = limiter.checkLimit("user:expire", limit = 2, window = 300.milliseconds)
            result.shouldBeInstanceOf<RateLimitResult.Allowed>()
        }
    }

    test("should handle zero limit") {
        val result = limiter.checkLimit("user:zero", limit = 0, window = 10.seconds)
        result.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }

    test("should handle single request limit") {
        val result1 = limiter.checkLimit("user:single", limit = 1, window = 10.seconds)
        result1.shouldBeInstanceOf<RateLimitResult.Allowed>()

        val result2 = limiter.checkLimit("user:single", limit = 1, window = 10.seconds)
        result2.shouldBeInstanceOf<RateLimitResult.Exceeded>()
    }
})
