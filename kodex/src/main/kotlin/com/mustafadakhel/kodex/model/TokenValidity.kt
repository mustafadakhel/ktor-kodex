package com.mustafadakhel.kodex.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

public data class TokenValidity(
    val access: Duration = 2.hours,
    val refresh: Duration = 90.days
) {
    internal companion object {
        val Default: TokenValidity = TokenValidity()
    }
}
