package com.mustafadakhel.kodex.tokens

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ExpirationCalculatorTest : StringSpec({
    "calculateExpiration with Duration adds duration to current time" {
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val duration = 24.hours
        val timeZone = TimeZone.UTC

        val result = ExpirationCalculator.calculateExpiration(duration, timeZone, now)

        result shouldBe Instant.parse("2024-01-02T12:00:00Z").toLocalDateTime(timeZone)
    }

    "calculateExpiration with milliseconds adds duration to current time" {
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val durationMs = 3600000L // 1 hour in milliseconds
        val timeZone = TimeZone.UTC

        val result = ExpirationCalculator.calculateExpiration(durationMs, timeZone, now)

        result shouldBe Instant.parse("2024-01-01T13:00:00Z").toLocalDateTime(timeZone)
    }

    "calculateExpiration handles different timezones" {
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val duration = 1.hours
        val estTimeZone = TimeZone.of("America/New_York")

        val result = ExpirationCalculator.calculateExpiration(duration, estTimeZone, now)

        result shouldBe Instant.parse("2024-01-01T13:00:00Z").toLocalDateTime(estTimeZone)
    }

    "calculateExpiration with fractional hours" {
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val duration = 30.minutes
        val timeZone = TimeZone.UTC

        val result = ExpirationCalculator.calculateExpiration(duration, timeZone, now)

        result shouldBe Instant.parse("2024-01-01T12:30:00Z").toLocalDateTime(timeZone)
    }

    "calculateExpiration with milliseconds for common durations" {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val timeZone = TimeZone.UTC

        // 15 minutes
        val result1 = ExpirationCalculator.calculateExpiration(900000L, timeZone, now)
        result1 shouldBe Instant.parse("2024-01-01T00:15:00Z").toLocalDateTime(timeZone)

        // 24 hours
        val result2 = ExpirationCalculator.calculateExpiration(86400000L, timeZone, now)
        result2 shouldBe Instant.parse("2024-01-02T00:00:00Z").toLocalDateTime(timeZone)
    }
})
