package com.mustafadakhel.kodex.ratelimit.inmemory

import com.mustafadakhel.kodex.ratelimit.RateLimiter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Create an in-memory rate limiter with default configuration.
 *
 * @param maxEntries Maximum number of rate limit keys to track (default: 100,000)
 * @param cleanupAge How old entries must be before cleanup (default: 1 minute)
 * @return Configured InMemoryRateLimiter instance
 */
public fun inMemoryRateLimiter(
    maxEntries: Int = 100_000,
    cleanupAge: Duration = 1.minutes
): RateLimiter = InMemoryRateLimiter(
    maxEntries = maxEntries,
    cleanupAge = cleanupAge
)
