package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.validation.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class VerificationConfigTest : FunSpec({

    val mockEventBus = object : EventBus {
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

    context("Valid configurations") {
        test("valid configuration passes validation") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 24.hours
                maxSendAttemptsPerUser = 5
                maxSendAttemptsPerContact = 5
                maxSendAttemptsPerIp = 10
                maxVerifyAttemptsPerUserIp = 5
                minVerificationResponseTimeMs = 100
                sendRateLimitWindow = 15.minutes
                verifyRateLimitWindow = 5.minutes
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }

        test("build succeeds for valid configuration with MANUAL strategy") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 24.hours
                maxSendAttemptsPerUser = 5
                maxSendAttemptsPerContact = 5
                maxSendAttemptsPerIp = 10
                maxVerifyAttemptsPerUserIp = 5
                minVerificationResponseTimeMs = 100
                sendRateLimitWindow = 15.minutes
                verifyRateLimitWindow = 5.minutes
            }

            // Should not throw
            val extension = config.build(testContext)
            (extension != null) shouldBe true
        }
    }

    context("defaultTokenExpiration validation") {
        test("negative defaultTokenExpiration fails validation") {
            val config = VerificationConfig().apply {
                defaultTokenExpiration = (-1).hours
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors() shouldContain "defaultTokenExpiration must be positive, got: -1h"
        }

        test("zero defaultTokenExpiration fails validation") {
            val config = VerificationConfig().apply {
                defaultTokenExpiration = 0.hours
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("defaultTokenExpiration must be positive") } shouldBe true
        }
    }

    context("Rate limit validation") {
        test("negative maxSendAttemptsPerUser fails validation") {
            val config = VerificationConfig().apply {
                maxSendAttemptsPerUser = -1
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxSendAttemptsPerUser must be positive") } shouldBe true
        }

        test("zero maxSendAttemptsPerUser fails validation") {
            val config = VerificationConfig().apply {
                maxSendAttemptsPerUser = 0
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxSendAttemptsPerUser must be positive") } shouldBe true
        }

        test("negative maxSendAttemptsPerContact fails validation") {
            val config = VerificationConfig().apply {
                maxSendAttemptsPerContact = -1
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxSendAttemptsPerContact must be positive") } shouldBe true
        }

        test("negative maxSendAttemptsPerIp fails validation") {
            val config = VerificationConfig().apply {
                maxSendAttemptsPerIp = -1
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxSendAttemptsPerIp must be positive") } shouldBe true
        }

        test("negative maxVerifyAttemptsPerUserIp fails validation") {
            val config = VerificationConfig().apply {
                maxVerifyAttemptsPerUserIp = -1
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("maxVerifyAttemptsPerUserIp must be positive") } shouldBe true
        }

        test("negative sendRateLimitWindow fails validation") {
            val config = VerificationConfig().apply {
                sendRateLimitWindow = (-1).minutes
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("sendRateLimitWindow must be positive") } shouldBe true
        }

        test("negative verifyRateLimitWindow fails validation") {
            val config = VerificationConfig().apply {
                verifyRateLimitWindow = (-1).minutes
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("verifyRateLimitWindow must be positive") } shouldBe true
        }
    }

    context("minVerificationResponseTimeMs validation") {
        test("negative minVerificationResponseTimeMs fails validation") {
            val config = VerificationConfig().apply {
                minVerificationResponseTimeMs = -1
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("minVerificationResponseTimeMs must be non-negative") } shouldBe true
        }

        test("excessive minVerificationResponseTimeMs fails validation") {
            val config = VerificationConfig().apply {
                minVerificationResponseTimeMs = 30_000 // 30 seconds
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("should be less than 10 seconds to avoid request timeouts") } shouldBe true
        }
    }

    context("Policy validation") {
        test("autoSend without sender fails validation") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED
                email {
                    autoSend = true
                    sender = null
                }
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("autoSend is true for email but no sender is configured") } shouldBe true
        }

        test("required contact without sender fails validation for non-MANUAL strategy") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED
                email {
                    required = true
                    sender = null
                }
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("Contact email is required but no sender is configured") } shouldBe true
        }

        test("VERIFY_REQUIRED_ONLY without required contacts fails validation") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY
                email {
                    required = false
                }
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any {
                it.contains("Strategy is VERIFY_REQUIRED_ONLY but no contacts are marked as required")
            } shouldBe true
        }

        test("negative policy tokenExpiration fails validation") {
            val config = VerificationConfig().apply {
                email {
                    tokenExpiration = (-1).hours
                }
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("Token expiration for email must be positive") } shouldBe true
        }
    }

    context("Multiple errors") {
        test("multiple validation errors are collected") {
            val config = VerificationConfig().apply {
                defaultTokenExpiration = (-1).hours
                maxSendAttemptsPerUser = 0
                minVerificationResponseTimeMs = 30_000
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors() shouldHaveSize 3
        }
    }

    context("Cooldown validation") {
        test("null cooldown is valid (optional parameter)") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = null
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }

        test("valid cooldown passes validation") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = 30.seconds
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }

        test("negative cooldown fails validation") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = (-5).seconds
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("sendCooldownPeriod must be positive") } shouldBe true
        }

        test("cooldown less than 1 second fails validation") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = 500.milliseconds
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("sendCooldownPeriod should be at least 1 second") } shouldBe true
        }

        test("cooldown greater than 1 hour fails validation") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = 90.minutes
            }

            val result = config.validate()
            (result is ValidationResult.Invalid) shouldBe true
            result.errors().any { it.contains("sendCooldownPeriod should not exceed 1 hour") } shouldBe true
        }

        test("cooldown of exactly 1 hour is valid") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = 60.minutes
            }

            val result = config.validate()
            result.isValid() shouldBe true
        }
    }

    context("Build method validation") {
        test("build throws IllegalStateException for invalid configuration") {
            val config = VerificationConfig().apply {
                defaultTokenExpiration = (-1).hours
                maxSendAttemptsPerUser = 0
            }

            val exception = shouldThrow<IllegalStateException> {
                config.build(testContext)
            }

            exception.message shouldContain "VerificationConfig validation failed"
            exception.message shouldContain "defaultTokenExpiration must be positive"
        }

        test("build throws for invalid cooldown") {
            val config = VerificationConfig().apply {
                sendCooldownPeriod = 90.minutes  // Too long
                email {
                    sender = object : VerificationSender {
                        override suspend fun send(destination: String, token: String) {}
                    }
                }
            }

            val exception = shouldThrow<IllegalStateException> {
                config.build(testContext)
            }

            exception.message shouldContain "sendCooldownPeriod should not exceed 1 hour"
        }
    }
})
