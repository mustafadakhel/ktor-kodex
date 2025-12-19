package com.mustafadakhel.kodex.ratelimit.redis

import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.RateLimitReservation
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import kotlinx.coroutines.future.await
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * Redis Cluster-backed rate limiter using Lettuce client with sliding window algorithm.
 * Supports multi-node Redis Cluster for high availability.
 */
public class RedisClusterRateLimiter(
    private val connection: StatefulRedisClusterConnection<String, String>,
    private val keyPrefix: String,
    private val circuitBreaker: CircuitBreaker,
    private val fallbackRateLimiter: RateLimiter
) : RateLimiter {

    private val async = connection.async()

    override suspend fun checkLimit(
        key: String,
        limit: Int,
        window: Duration
    ): RateLimitResult {
        if (circuitBreaker.isOpen) {
            return fallbackRateLimiter.checkLimit(key, limit, window)
        }

        val redisKey = "$keyPrefix$key"
        val now = CurrentKotlinInstant
        val windowStart = now - window

        return try {
            val result = async.eval<Long>(
                LuaScripts.CHECK_LIMIT,
                ScriptOutputType.INTEGER,
                arrayOf(redisKey),
                windowStart.toEpochMilliseconds().toString(),
                now.toEpochMilliseconds().toString(),
                limit.toString(),
                window.inWholeSeconds.toString()
            ).await()

            circuitBreaker.recordSuccess()

            if (result == -1L) {
                RateLimitResult.Exceeded("Rate limit exceeded: $limit per $window")
            } else {
                RateLimitResult.Allowed
            }
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            fallbackRateLimiter.checkLimit(key, limit, window)
        }
    }

    override suspend fun checkAndReserve(
        key: String,
        limit: Int,
        window: Duration,
        cooldown: Duration?
    ): RateLimitReservation {
        if (circuitBreaker.isOpen) {
            return fallbackRateLimiter.checkAndReserve(key, limit, window, cooldown)
        }

        val redisKey = "$keyPrefix$key"
        val now = CurrentKotlinInstant
        val windowStart = now - window

        return try {
            if (cooldown != null) {
                val lastAttemptResult = async.eval<String>(
                    LuaScripts.GET_LAST_ATTEMPT,
                    ScriptOutputType.VALUE,
                    arrayOf(redisKey),
                    windowStart.toEpochMilliseconds().toString()
                ).await()

                if (lastAttemptResult != null) {
                    val lastAttemptTime = Instant.fromEpochMilliseconds(lastAttemptResult.toLong())
                    val timeSinceLastAttempt = now - lastAttemptTime

                    if (timeSinceLastAttempt < cooldown) {
                        val retryAfter = lastAttemptTime + cooldown
                        return RateLimitReservation(
                            result = RateLimitResult.Cooldown(
                                reason = "Cooldown period not elapsed. Minimum $cooldown between requests.",
                                retryAfter = retryAfter
                            ),
                            reservationId = null
                        )
                    }
                }
            }

            val reservationId = UUID.randomUUID().toString()
            val result = async.eval<String>(
                LuaScripts.RESERVE,
                ScriptOutputType.VALUE,
                arrayOf(redisKey),
                windowStart.toEpochMilliseconds().toString(),
                now.toEpochMilliseconds().toString(),
                limit.toString(),
                window.inWholeSeconds.toString(),
                reservationId
            ).await()

            circuitBreaker.recordSuccess()

            if (result == "EXCEEDED") {
                RateLimitReservation(
                    result = RateLimitResult.Exceeded("Rate limit exceeded: $limit per $window"),
                    reservationId = null
                )
            } else {
                RateLimitReservation(
                    result = RateLimitResult.Allowed,
                    reservationId = "$redisKey:$result"
                )
            }
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            fallbackRateLimiter.checkAndReserve(key, limit, window, cooldown)
        }
    }

    override suspend fun releaseReservation(reservationId: String?) {
        if (reservationId == null) return
        if (circuitBreaker.isOpen) {
            fallbackRateLimiter.releaseReservation(reservationId)
            return
        }

        val parts = reservationId.split(":", limit = 2)
        if (parts.size != 2) return

        val redisKey = parts[0]
        val actualReservationId = parts[1]

        try {
            async.eval<Long>(
                LuaScripts.RELEASE,
                ScriptOutputType.INTEGER,
                arrayOf(redisKey),
                actualReservationId
            ).await()
            circuitBreaker.recordSuccess()
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            fallbackRateLimiter.releaseReservation(reservationId)
        }
    }

    override suspend fun clear(key: String) {
        val redisKey = "$keyPrefix$key"
        if (circuitBreaker.isOpen) {
            fallbackRateLimiter.clear(key)
            return
        }

        try {
            async.del(redisKey).await()
            circuitBreaker.recordSuccess()
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            fallbackRateLimiter.clear(key)
        }
    }

    override suspend fun clearAll() {
        if (circuitBreaker.isOpen) {
            fallbackRateLimiter.clearAll()
            return
        }

        try {
            val pattern = "$keyPrefix*"
            val allKeys = mutableSetOf<String>()

            val partitions = connection.partitions
            for (partition in partitions) {
                if (partition.`is`(io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag.UPSTREAM)) {
                    val nodeConnection = connection.getConnection(partition.nodeId)
                    var cursor = io.lettuce.core.ScanCursor.INITIAL

                    do {
                        val scanResult = nodeConnection.sync().scan(
                            cursor,
                            io.lettuce.core.ScanArgs.Builder.matches(pattern).limit(1000)
                        )
                        allKeys.addAll(scanResult.keys)
                        cursor = io.lettuce.core.ScanCursor.of(scanResult.cursor)
                    } while (!cursor.isFinished)
                }
            }

            if (allKeys.isNotEmpty()) {
                async.del(*allKeys.toTypedArray()).await()
            }
            circuitBreaker.recordSuccess()
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            fallbackRateLimiter.clearAll()
        }
    }

    /**
     * Close the Redis cluster connection.
     * Should be called on application shutdown.
     */
    public fun close() {
        connection.close()
    }
}
