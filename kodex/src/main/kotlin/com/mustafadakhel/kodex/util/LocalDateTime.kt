package com.mustafadakhel.kodex.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

public val CurrentKotlinInstant: Instant get() = Clock.System.now()

public fun now(timeZone: TimeZone): LocalDateTime = CurrentKotlinInstant.toLocalDateTime(timeZone)
