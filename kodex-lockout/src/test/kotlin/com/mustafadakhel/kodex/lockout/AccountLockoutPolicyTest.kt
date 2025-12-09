package com.mustafadakhel.kodex.lockout

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Lockout policy presets tests.
 * Tests all preset configurations (strict, moderate, lenient, disabled).
 */
class AccountLockoutPolicyTest : StringSpec({

    "strict() policy should have 3 max failed attempts" {
        val policy = AccountLockoutPolicy.strict()
        policy.maxFailedAttempts shouldBe 3
    }

    "strict() policy should have 15 minute attempt window" {
        val policy = AccountLockoutPolicy.strict()
        policy.attemptWindow shouldBe 15.minutes
    }

    "strict() policy should have 1 hour lockout duration" {
        val policy = AccountLockoutPolicy.strict()
        policy.lockoutDuration shouldBe 1.hours
    }

    "strict() policy should be enabled" {
        val policy = AccountLockoutPolicy.strict()
        policy.enabled.shouldBeTrue()
    }

    "moderate() policy should have 5 max failed attempts" {
        val policy = AccountLockoutPolicy.moderate()
        policy.maxFailedAttempts shouldBe 5
    }

    "moderate() policy should have 15 minute attempt window" {
        val policy = AccountLockoutPolicy.moderate()
        policy.attemptWindow shouldBe 15.minutes
    }

    "moderate() policy should have 30 minute lockout duration" {
        val policy = AccountLockoutPolicy.moderate()
        policy.lockoutDuration shouldBe 30.minutes
    }

    "moderate() policy should be enabled" {
        val policy = AccountLockoutPolicy.moderate()
        policy.enabled.shouldBeTrue()
    }

    "lenient() policy should have 10 max failed attempts" {
        val policy = AccountLockoutPolicy.lenient()
        policy.maxFailedAttempts shouldBe 10
    }

    "lenient() policy should have 30 minute attempt window" {
        val policy = AccountLockoutPolicy.lenient()
        policy.attemptWindow shouldBe 30.minutes
    }

    "lenient() policy should have 15 minute lockout duration" {
        val policy = AccountLockoutPolicy.lenient()
        policy.lockoutDuration shouldBe 15.minutes
    }

    "lenient() policy should be enabled" {
        val policy = AccountLockoutPolicy.lenient()
        policy.enabled.shouldBeTrue()
    }

    "disabled() policy should NOT be enabled" {
        val policy = AccountLockoutPolicy.disabled()
        policy.enabled.shouldBeFalse()
    }

    "disabled() policy should have very high max attempts (effectively unlimited)" {
        val policy = AccountLockoutPolicy.disabled()
        policy.maxFailedAttempts shouldBe Int.MAX_VALUE
    }

    "Custom policy with 0 max attempts should throw" {
        shouldThrow<IllegalArgumentException> {
            AccountLockoutPolicy(maxFailedAttempts = 0)
        }
    }

    "Custom policy with negative max attempts should throw" {
        shouldThrow<IllegalArgumentException> {
            AccountLockoutPolicy(maxFailedAttempts = -1)
        }
    }

    "Custom policy with zero duration should throw" {
        shouldThrow<IllegalArgumentException> {
            AccountLockoutPolicy(
                attemptWindow = 0.minutes
            )
        }
    }

    "Custom policy with negative lockout duration should throw" {
        shouldThrow<IllegalArgumentException> {
            AccountLockoutPolicy(
                lockoutDuration = (-10).minutes
            )
        }
    }

    "Custom policy with valid parameters should succeed" {
        val policy = AccountLockoutPolicy(
            maxFailedAttempts = 2,
            attemptWindow = 10.minutes,
            lockoutDuration = 60.minutes,
            enabled = true
        )

        policy.maxFailedAttempts shouldBe 2
        policy.attemptWindow shouldBe 10.minutes
        policy.lockoutDuration shouldBe 60.minutes
        policy.enabled.shouldBeTrue()
    }

    "Default policy should match moderate() preset" {
        val defaultPolicy = AccountLockoutPolicy()
        val moderatePolicy = AccountLockoutPolicy.moderate()

        defaultPolicy.maxFailedAttempts shouldBe moderatePolicy.maxFailedAttempts
        defaultPolicy.attemptWindow shouldBe moderatePolicy.attemptWindow
        defaultPolicy.lockoutDuration shouldBe moderatePolicy.lockoutDuration
        defaultPolicy.enabled shouldBe moderatePolicy.enabled
    }
})
