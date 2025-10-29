package com.mustafadakhel.kodex.token

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public data class TokenRotationPolicy(
    val enabled: Boolean = true,
    val detectReplayAttacks: Boolean = true,
    val revokeOnReplay: Boolean = true,
    val gracePeriod: Duration = 5.seconds
) {
    public companion object {
        public fun strict(): TokenRotationPolicy = TokenRotationPolicy(
            enabled = true,
            detectReplayAttacks = true,
            revokeOnReplay = true,
            gracePeriod = 0.seconds
        )

        public fun balanced(): TokenRotationPolicy = TokenRotationPolicy()

        public fun lenient(): TokenRotationPolicy = TokenRotationPolicy(
            detectReplayAttacks = false,
            revokeOnReplay = false,
            gracePeriod = 10.seconds
        )

        public fun disabled(): TokenRotationPolicy = TokenRotationPolicy(enabled = false)
    }
}
