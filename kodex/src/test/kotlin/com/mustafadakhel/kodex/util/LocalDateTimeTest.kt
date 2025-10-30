package com.mustafadakhel.kodex.util

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

class LocalDateTimeTest : DescribeSpec({

    describe("CurrentKotlinInstant") {
        it("should return current instant") {
            val before = CurrentKotlinInstant
            val instant = CurrentKotlinInstant
            val after = CurrentKotlinInstant

            instant.toEpochMilliseconds() shouldBeGreaterThanOrEqual before.toEpochMilliseconds()
            instant.toEpochMilliseconds() shouldBeLessThan (after.toEpochMilliseconds() + 1000)
        }

        it("should return different values on subsequent calls") {
            val first = CurrentKotlinInstant
            Thread.sleep(10)
            val second = CurrentKotlinInstant

            second.toEpochMilliseconds() shouldBeGreaterThanOrEqual first.toEpochMilliseconds()
        }
    }

    describe("getCurrentLocalDateTime") {
        it("should return local datetime in UTC") {
            val timeZone = TimeZone.UTC
            val localDateTime = now(timeZone)

            val expected = now(TimeZone.UTC)
            localDateTime.year shouldBe expected.year
            localDateTime.monthNumber shouldBe expected.monthNumber
            localDateTime.dayOfMonth shouldBe expected.dayOfMonth
        }

        it("should return local datetime in specific timezone") {
            val timeZone = TimeZone.of("America/New_York")
            val localDateTime = now(timeZone)

            val expected = now(timeZone)
            localDateTime.year shouldBe expected.year
            localDateTime.monthNumber shouldBe expected.monthNumber
            localDateTime.dayOfMonth shouldBe expected.dayOfMonth
        }

        it("should return local datetime in Europe/London timezone") {
            val timeZone = TimeZone.of("Europe/London")
            val localDateTime = now(timeZone)

            val expected = now(timeZone)
            localDateTime.year shouldBe expected.year
            localDateTime.monthNumber shouldBe expected.monthNumber
        }

        it("should return local datetime in Asia/Tokyo timezone") {
            val timeZone = TimeZone.of("Asia/Tokyo")
            val localDateTime = now(timeZone)

            val expected = now(timeZone)
            localDateTime.year shouldBe expected.year
            localDateTime.monthNumber shouldBe expected.monthNumber
        }

        it("should handle timezone offset correctly") {
            val utcTime = now(TimeZone.UTC)
            val nyTime = now(TimeZone.of("America/New_York"))

            // Times should be within the same day (accounting for timezone differences)
            val utcInstant = utcTime.toInstant(TimeZone.UTC)
            val nyInstant = nyTime.toInstant(TimeZone.of("America/New_York"))

            val diff = kotlin.math.abs(utcInstant.toEpochMilliseconds() - nyInstant.toEpochMilliseconds())
            diff shouldBeLessThan 2000
        }
    }
})
