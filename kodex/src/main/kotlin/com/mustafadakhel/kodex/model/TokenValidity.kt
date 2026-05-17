package com.mustafadakhel.kodex.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

public data class TokenValidity(
    val access: Duration = 15.minutes,
    val refresh: Duration = 30.days
) {
    internal companion object {
        val Default: TokenValidity = TokenValidity()
    }
}
