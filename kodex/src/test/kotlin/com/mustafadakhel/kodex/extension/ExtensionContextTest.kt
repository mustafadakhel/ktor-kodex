package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.ratelimit.NoOpRateLimiter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.TimeZone

class ExtensionContextTest : DescribeSpec({

    val mockEventBus = mockk<EventBus>()
    val rateLimiter = NoOpRateLimiter()

    describe("ExtensionContext") {
        describe("extensionContext factory") {
            it("should create context with provided realm and timezone") {
                val realm = Realm(name = "test-realm")
                val timeZone = TimeZone.of("America/New_York")

                val context = extensionContext(realm, timeZone, mockEventBus, rateLimiter)

                context.realm shouldBe realm
                context.timeZone shouldBe timeZone
            }

            it("should create context with UTC timezone") {
                val realm = Realm(name = "utc-realm")
                val timeZone = TimeZone.UTC

                val context = extensionContext(realm, timeZone, mockEventBus, rateLimiter)

                context.realm shouldBe realm
                context.timeZone shouldBe TimeZone.UTC
            }

            it("should handle different realm names") {
                val realm1 = Realm(name = "realm-1")
                val realm2 = Realm(name = "realm-2")
                val timeZone = TimeZone.UTC

                val context1 = extensionContext(realm1, timeZone, mockEventBus, rateLimiter)
                val context2 = extensionContext(realm2, timeZone, mockEventBus, rateLimiter)

                context1.realm.name shouldBe "realm-1"
                context2.realm.name shouldBe "realm-2"
            }
        }

        describe("ExtensionContextImpl") {
            it("should be a data class with equals") {
                val realm = Realm(name = "test-realm")
                val timeZone = TimeZone.UTC

                val context1 = extensionContext(realm, timeZone, mockEventBus, rateLimiter) as ExtensionContextImpl
                val context2 = extensionContext(realm, timeZone, mockEventBus, rateLimiter) as ExtensionContextImpl

                (context1.realm == context2.realm) shouldBe true
                (context1.timeZone == context2.timeZone) shouldBe true
            }

            it("should have different instances for different realms") {
                val realm1 = Realm(name = "realm-1")
                val realm2 = Realm(name = "realm-2")
                val timeZone = TimeZone.UTC

                val context1 = extensionContext(realm1, timeZone, mockEventBus, rateLimiter)
                val context2 = extensionContext(realm2, timeZone, mockEventBus, rateLimiter)

                (context1.realm == context2.realm) shouldBe false
            }

            it("should have different instances for different timezones") {
                val realm = Realm(name = "test-realm")
                val tz1 = TimeZone.UTC
                val tz2 = TimeZone.of("America/Los_Angeles")

                val context1 = extensionContext(realm, tz1, mockEventBus, rateLimiter)
                val context2 = extensionContext(realm, tz2, mockEventBus, rateLimiter)

                (context1.timeZone == context2.timeZone) shouldBe false
            }

            it("should provide access to realm properties") {
                val realm = Realm(name = "my-app")
                val context = extensionContext(realm, TimeZone.UTC, mockEventBus, rateLimiter)

                context.realm.name shouldBe "my-app"
            }

            it("should provide access to timezone") {
                val realm = Realm(name = "test")
                val timeZone = TimeZone.of("Europe/London")
                val context = extensionContext(realm, timeZone, mockEventBus, rateLimiter)

                context.timeZone shouldBe timeZone
            }
        }
    }
})
