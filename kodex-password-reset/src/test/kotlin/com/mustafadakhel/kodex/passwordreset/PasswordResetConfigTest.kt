package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.validation.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PasswordResetConfigTest : FunSpec({

    val mockEventBus = object : com.mustafadakhel.kodex.event.EventBus {
        override suspend fun publish(event: com.mustafadakhel.kodex.event.KodexEvent) {}
        override fun <T : com.mustafadakhel.kodex.event.KodexEvent> subscribe(subscriber: com.mustafadakhel.kodex.event.EventSubscriber<T>) {}
        override fun <T : com.mustafadakhel.kodex.event.KodexEvent> unsubscribe(subscriber: com.mustafadakhel.kodex.event.EventSubscriber<T>) {}
        override fun shutdown() {}
    }

    val testContext = object : ExtensionContext {
        override val realm = Realm(owner = "test")
        override val timeZone = TimeZone.UTC
        override val eventBus = mockEventBus
    }

    val mockSender = object : PasswordResetSender {
        override suspend fun send(recipient: String, token: String, expiresAt: String) {
            // Mock implementation
        }
    }

    context("Valid configurations") {
        test("valid configuration passes validation") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 3
                maxAttemptsPerIdentifier = 5
                maxAttemptsPerIp = 10
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }

        test("build succeeds for valid configuration") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 3
                maxAttemptsPerIdentifier = 5
                maxAttemptsPerIp = 10
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            // Should not throw
            val extension = config.build(testContext)
            (extension != null) shouldBe true
        }
    }

    context("tokenValidity validation") {
        test("negative tokenValidity fails validation") {
            val config = PasswordResetConfig().apply {
                tokenValidity = (-1).hours
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("tokenValidity must be positive") } shouldBe true
        }

        test("zero tokenValidity fails validation") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 0.hours
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("tokenValidity must be positive") } shouldBe true
        }

        test("tokenValidity less than 5 minutes fails validation") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 30.seconds
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("tokenValidity should be at least 5 minutes for usability") } shouldBe true
        }

        test("tokenValidity exceeding 24 hours fails validation") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 48.hours
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("tokenValidity should not exceed 24 hours for security") } shouldBe true
        }
    }

    context("Rate limit validation") {
        test("negative maxAttemptsPerUser fails validation") {
            val config = PasswordResetConfig().apply {
                maxAttemptsPerUser = -1
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxAttemptsPerUser must be positive") } shouldBe true
        }

        test("zero maxAttemptsPerUser fails validation") {
            val config = PasswordResetConfig().apply {
                maxAttemptsPerUser = 0
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxAttemptsPerUser must be positive") } shouldBe true
        }

        test("negative maxAttemptsPerIdentifier fails validation") {
            val config = PasswordResetConfig().apply {
                maxAttemptsPerIdentifier = -1
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxAttemptsPerIdentifier must be positive") } shouldBe true
        }

        test("negative maxAttemptsPerIp fails validation") {
            val config = PasswordResetConfig().apply {
                maxAttemptsPerIp = -1
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxAttemptsPerIp must be positive") } shouldBe true
        }

        test("negative rateLimitWindow fails validation") {
            val config = PasswordResetConfig().apply {
                rateLimitWindow = (-1).minutes
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("rateLimitWindow must be positive") } shouldBe true
        }
    }

    context("Required configuration") {
        test("null passwordResetSender fails validation") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                passwordResetSender = null
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("passwordResetSender must not be null") } shouldBe true
        }
    }

    context("Multiple errors") {
        test("multiple validation errors are collected") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 48.hours // Too long
                maxAttemptsPerUser = 0 // Invalid
                maxAttemptsPerIp = -1 // Invalid
                passwordResetSender = null // Missing
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors() shouldHaveSize 4
        }
    }

    context("Cooldown validation") {
        test("null cooldown is valid (optional parameter)") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = null
                passwordResetSender = mockSender
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }

        test("valid cooldown passes validation") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = 30.seconds
                passwordResetSender = mockSender
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }

        test("negative cooldown fails validation") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = (-5).seconds
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("cooldownPeriod must be positive") } shouldBe true
        }

        test("cooldown less than 1 second fails validation") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = 500.milliseconds
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("cooldownPeriod should be at least 1 second") } shouldBe true
        }

        test("cooldown greater than 1 hour fails validation") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = 90.minutes
                passwordResetSender = mockSender
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("cooldownPeriod should not exceed 1 hour") } shouldBe true
        }

        test("cooldown of exactly 1 hour is valid") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = 60.minutes
                passwordResetSender = mockSender
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }
    }

    context("Build method validation") {
        test("build throws IllegalStateException for invalid configuration") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 48.hours
                maxAttemptsPerUser = 0
                passwordResetSender = null
            }

            val exception = shouldThrow<IllegalStateException> {
                config.build(testContext)
            }

            exception.message shouldContain "PasswordResetConfig validation failed"
            exception.message shouldContain "tokenValidity should not exceed 24 hours for security"
        }

        test("build throws IllegalStateException even after validation shows error") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 30.seconds // Too short
                passwordResetSender = mockSender
            }

            // Manually validate first
            val validationResult = config.validate()
            (validationResult.isValid() == false) shouldBe true

            // Build should still throw even if we already know validation failed
            shouldThrow<IllegalStateException> {
                config.build(testContext)
            }
        }

        test("build throws for invalid cooldown") {
            val config = PasswordResetConfig().apply {
                cooldownPeriod = 90.minutes  // Too long
                passwordResetSender = mockSender
            }

            val exception = shouldThrow<IllegalStateException> {
                config.build(testContext)
            }

            exception.message shouldContain "cooldownPeriod should not exceed 1 hour"
        }
    }
})
