package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import io.ktor.utils.io.*
import kotlin.time.Duration

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
        TokenType.RefreshToken to true,
    )
    private var accessDuration: Duration = TokenValidity.Default.access
    private var refreshDuration: Duration = TokenValidity.Default.refresh

    override fun access(duration: Duration) {
        accessDuration = duration
    }

    override fun refresh(duration: Duration) {
        refreshDuration = duration
    }

    override fun persist(tokenType: TokenType, persist: Boolean) {
        persistenceFlags[tokenType] = persist
    }


    internal fun validity(): TokenValidity = TokenValidity(accessDuration, refreshDuration)
}
