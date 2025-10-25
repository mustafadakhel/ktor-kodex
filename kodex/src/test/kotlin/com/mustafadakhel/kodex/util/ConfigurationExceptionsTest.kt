package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ConfigurationExceptionsTest : DescribeSpec({

    describe("ConfigurationException") {
        it("should be subclass of IllegalStateException") {
            val exception = KodexNotConfiguredException()
            exception.shouldBeInstanceOf<IllegalStateException>()
        }

        it("should have message property") {
            val exception = KodexNotConfiguredException("Custom message")
            exception.message shouldBe "Custom message"
        }
    }

    describe("MissingRealmServiceException") {
        it("should include realm in message") {
            val realm = Realm(owner = "test-realm")
            val exception = MissingRealmServiceException(realm)

            exception.message shouldContain "test-realm"
            exception.message shouldContain "No service found for realm"
        }

        it("should be a ConfigurationException") {
            val realm = Realm(owner = "my-app")
            val exception = MissingRealmServiceException(realm)

            exception.shouldBeInstanceOf<ConfigurationException>()
        }

        it("should include full realm details in message") {
            val realm = Realm(owner = "production-app")
            val exception = MissingRealmServiceException(realm)

            exception.message shouldContain realm.toString()
        }
    }

    describe("MissingRealmConfigException") {
        it("should include realm in message") {
            val realm = Realm(owner = "test-realm")
            val exception = MissingRealmConfigException(realm)

            exception.message shouldContain "test-realm"
            exception.message shouldContain "No realm configuration found for realm"
        }

        it("should be a ConfigurationException") {
            val realm = Realm(owner = "my-app")
            val exception = MissingRealmConfigException(realm)

            exception.shouldBeInstanceOf<ConfigurationException>()
        }

        it("should differentiate from MissingRealmServiceException") {
            val realm = Realm(owner = "test-realm")
            val serviceException = MissingRealmServiceException(realm)
            val configException = MissingRealmConfigException(realm)

            serviceException.message shouldContain "No service found"
            configException.message shouldContain "No realm configuration found"
        }
    }

    describe("KodexNotConfiguredException") {
        it("should have default message") {
            val exception = KodexNotConfiguredException()
            exception.message shouldBe "Kodex not configured"
        }

        it("should allow custom message") {
            val exception = KodexNotConfiguredException("Database not initialized")
            exception.message shouldBe "Database not initialized"
        }

        it("should be a ConfigurationException") {
            val exception = KodexNotConfiguredException()
            exception.shouldBeInstanceOf<ConfigurationException>()
        }

        it("should support detailed custom messages") {
            val customMessage = "Kodex plugin not installed. Please call install { kodex { ... } } in your Application module."
            val exception = KodexNotConfiguredException(customMessage)

            exception.message shouldBe customMessage
        }
    }

    describe("NoSuchRoleException") {
        it("should include role name in message") {
            val exception = NoSuchRoleException("admin")

            exception.message shouldContain "admin"
            exception.message shouldContain "does not exist"
        }

        it("should be a Throwable") {
            val exception = NoSuchRoleException("user")
            exception.shouldBeInstanceOf<Throwable>()
        }

        it("should have descriptive message format") {
            val exception = NoSuchRoleException("superadmin")
            exception.message shouldBe "Role superadmin does not exist"
        }

        it("should handle role names with special characters") {
            val exception = NoSuchRoleException("admin:write")
            exception.message shouldContain "admin:write"
        }

        it("should handle empty role name") {
            val exception = NoSuchRoleException("")
            exception.message shouldBe "Role  does not exist"
        }
    }
})
