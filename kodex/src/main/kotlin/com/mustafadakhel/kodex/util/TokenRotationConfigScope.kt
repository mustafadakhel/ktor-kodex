package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.token.TokenRotationPolicy
import io.ktor.utils.io.*

internal data class TokenRotationConfig(
    val policy: TokenRotationPolicy
)

@KtorDsl
public class TokenRotationConfigScope internal constructor() {
    public var policy: TokenRotationPolicy = TokenRotationPolicy.balanced()

    internal fun build(): TokenRotationConfig = TokenRotationConfig(
        policy = policy
    )
}
