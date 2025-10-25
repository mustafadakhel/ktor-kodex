package com.mustafadakhel.kodex.util

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal val CurrentKotlinInstant get() = Clock.System.now()

internal fun now(timeZone: TimeZone) = CurrentKotlinInstant.toLocalDateTime(timeZone)
