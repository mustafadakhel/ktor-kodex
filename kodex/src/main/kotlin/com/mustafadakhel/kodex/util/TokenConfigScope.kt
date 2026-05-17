package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Scope used to configure how long tokens remain valid and whether refresh
 * tokens should be persisted.
 */
public interface TokenConfigScope {
    public fun access(duration: Duration)
    public fun refresh(duration: Duration)
    public fun persist(tokenType: TokenType, persist: Boolean)
}

@KtorDsl
internal class TokenConfig : TokenConfigScope {
    internal val persistenceFlags: MutableMap<TokenType, Boolean> = mutableMapOf(
        TokenType.AccessToken to true,
        TokenType.RefreshToken to true,
    )
    private var accessDuration: Duration = TokenValidity.Default.access
    private var refreshDuration: Duration = TokenValidity.Default.refresh

    override fun access(duration: Duration) {
        require(duration.isFinite()) { "Access token duration must be finite" }
        require(duration <= MAX_ACCESS_DURATION) {
            "Access token duration cannot exceed $MAX_ACCESS_DURATION. Got: $duration"
        }
        accessDuration = duration
    }

    override fun refresh(duration: Duration) {
        require(duration.isFinite()) { "Refresh token duration must be finite" }
        require(duration <= MAX_REFRESH_DURATION) {
            "Refresh token duration cannot exceed $MAX_REFRESH_DURATION. Got: $duration"
        }
        refreshDuration = duration
    }

    internal companion object {
        val MAX_ACCESS_DURATION: Duration = 24.hours
        val MAX_REFRESH_DURATION: Duration = 90.days
    }

    override fun persist(tokenType: TokenType, persist: Boolean) {
        persistenceFlags[tokenType] = persist
    }


    internal fun validity(): TokenValidity = TokenValidity(accessDuration, refreshDuration)
}
