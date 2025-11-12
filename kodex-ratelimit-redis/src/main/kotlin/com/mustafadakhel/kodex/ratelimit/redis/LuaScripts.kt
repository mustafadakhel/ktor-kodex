package com.mustafadakhel.kodex.ratelimit.redis

/**
 * Lua scripts for atomic Redis operations.
 */
internal object LuaScripts {
    /**
     * Sliding window rate limit check using sorted sets.
     *
     * KEYS[1] = rate limit key
     * ARGV[1] = window start timestamp (milliseconds)
     * ARGV[2] = current timestamp (milliseconds)
     * ARGV[3] = limit
     * ARGV[4] = window duration (seconds) for TTL
     *
     * Returns:
     * - count if allowed (count <= limit)
     * - -1 if exceeded
     */
    const val CHECK_LIMIT = """
        local key = KEYS[1]
        local window_start = tonumber(ARGV[1])
        local now = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local ttl = tonumber(ARGV[4])

        -- Remove entries outside window
        redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

        -- Count current entries
        local count = redis.call('ZCARD', key)

        if count < limit then
            -- Under limit, add entry
            redis.call('ZADD', key, now, now)
            redis.call('EXPIRE', key, ttl)
            return count + 1
        else
            -- Over limit
            return -1
        end
    """

    /**
     * Reserve a slot in the rate limit (with rollback support).
     *
     * KEYS[1] = rate limit key
     * ARGV[1] = window start timestamp (milliseconds)
     * ARGV[2] = current timestamp (milliseconds)
     * ARGV[3] = limit
     * ARGV[4] = window duration (seconds) for TTL
     * ARGV[5] = reservation ID (unique)
     *
     * Returns:
     * - reservation ID if allowed
     * - "EXCEEDED" if over limit
     */
    const val RESERVE = """
        local key = KEYS[1]
        local window_start = tonumber(ARGV[1])
        local now = tonumber(ARGV[2])
        local limit = tonumber(ARGV[3])
        local ttl = tonumber(ARGV[4])
        local reservation_id = ARGV[5]

        -- Remove entries outside window
        redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

        -- Count current entries
        local count = redis.call('ZCARD', key)

        if count < limit then
            -- Under limit, add reservation
            redis.call('ZADD', key, now, reservation_id)
            redis.call('EXPIRE', key, ttl)
            return reservation_id
        else
            -- Over limit
            return "EXCEEDED"
        end
    """

    /**
     * Release a reservation (rollback).
     *
     * KEYS[1] = rate limit key
     * ARGV[1] = reservation ID
     *
     * Returns: number of entries removed (0 or 1)
     */
    const val RELEASE = """
        local key = KEYS[1]
        local reservation_id = ARGV[1]

        return redis.call('ZREM', key, reservation_id)
    """

    /**
     * Get last attempt timestamp for cooldown checking.
     *
     * KEYS[1] = rate limit key
     * ARGV[1] = window start timestamp (milliseconds)
     *
     * Returns: last attempt timestamp or nil
     */
    const val GET_LAST_ATTEMPT = """
        local key = KEYS[1]
        local window_start = tonumber(ARGV[1])

        -- Remove entries outside window
        redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

        -- Get highest score (most recent attempt)
        local entries = redis.call('ZREVRANGE', key, 0, 0, 'WITHSCORES')

        if #entries > 0 then
            return entries[2]  -- Score (timestamp)
        else
            return nil
        end
    """
}
