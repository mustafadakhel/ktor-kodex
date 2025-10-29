package com.mustafadakhel.kodex.security

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public data class RateLimitPolicy(
    val maxAttempts: Int = 5,
    val window: Duration = 15.minutes
) {
    init {
        require(maxAttempts > 0) { "Max attempts must be positive" }
        require(window.isPositive()) { "Window must be positive" }
    }

    public companion object {
        public fun strict(): RateLimitPolicy = RateLimitPolicy(
            maxAttempts = 3,
            window = 15.minutes
        )

        public fun moderate(): RateLimitPolicy = RateLimitPolicy(
            maxAttempts = 5,
            window = 15.minutes
        )

        public fun lenient(): RateLimitPolicy = RateLimitPolicy(
            maxAttempts = 10,
            window = 15.minutes
        )
    }
}
