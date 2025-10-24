package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.Realm
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone

class ExtensionContextTest : DescribeSpec({

    describe("ExtensionContext") {
        describe("extensionContext factory") {
            it("should create context with provided realm and timezone") {
                val realm = Realm(owner = "test-realm")
                val timeZone = TimeZone.of("America/New_York")

                val context = extensionContext(realm, timeZone)

                context.realm shouldBe realm
                context.timeZone shouldBe timeZone
            }

            it("should create context with UTC timezone") {
                val realm = Realm(owner = "utc-realm")
                val timeZone = TimeZone.UTC

                val context = extensionContext(realm, timeZone)

                context.realm shouldBe realm
                context.timeZone shouldBe TimeZone.UTC
            }

            it("should handle different realm owners") {
                val realm1 = Realm(owner = "realm-1")
                val realm2 = Realm(owner = "realm-2")
                val timeZone = TimeZone.UTC

                val context1 = extensionContext(realm1, timeZone)
                val context2 = extensionContext(realm2, timeZone)

                context1.realm.owner shouldBe "realm-1"
                context2.realm.owner shouldBe "realm-2"
            }
        }

        describe("ExtensionContextImpl") {
            it("should be a data class with equals") {
                val realm = Realm(owner = "test-realm")
                val timeZone = TimeZone.UTC

                val context1 = ExtensionContextImpl(realm, timeZone)
                val context2 = ExtensionContextImpl(realm, timeZone)

                (context1 == context2) shouldBe true
            }

            it("should have different instances for different realms") {
                val realm1 = Realm(owner = "realm-1")
                val realm2 = Realm(owner = "realm-2")
                val timeZone = TimeZone.UTC

                val context1 = ExtensionContextImpl(realm1, timeZone)
                val context2 = ExtensionContextImpl(realm2, timeZone)

                (context1 == context2) shouldBe false
            }

            it("should have different instances for different timezones") {
                val realm = Realm(owner = "test-realm")
                val tz1 = TimeZone.UTC
                val tz2 = TimeZone.of("America/Los_Angeles")

                val context1 = ExtensionContextImpl(realm, tz1)
                val context2 = ExtensionContextImpl(realm, tz2)

                (context1 == context2) shouldBe false
            }

            it("should provide access to realm properties") {
                val realm = Realm(owner = "my-app")
                val context = ExtensionContextImpl(realm, TimeZone.UTC)

                context.realm.owner shouldBe "my-app"
            }

            it("should provide access to timezone") {
                val realm = Realm(owner = "test")
                val timeZone = TimeZone.of("Europe/London")
                val context = ExtensionContextImpl(realm, timeZone)

                context.timeZone shouldBe timeZone
            }
        }
    }
})
