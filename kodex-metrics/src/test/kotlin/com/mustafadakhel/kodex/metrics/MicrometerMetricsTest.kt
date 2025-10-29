package com.mustafadakhel.kodex.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class MicrometerMetricsTest : FunSpec({

    lateinit var registry: SimpleMeterRegistry
    lateinit var metrics: MicrometerMetrics

    beforeEach {
        registry = SimpleMeterRegistry()
        metrics = MicrometerMetrics(registry)
    }

    context("Authentication Metrics") {

        test("should record successful authentication") {
            metrics.recordAuthentication(success = true, reason = null, realmId = "default")

            val counter = registry.counter(
                "kodex.authentication.attempts",
                "success", "true",
                "reason", "none",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should record failed authentication with reason") {
            metrics.recordAuthentication(success = false, reason = "invalid_credentials", realmId = "default")

            val counter = registry.counter(
                "kodex.authentication.attempts",
                "success", "false",
                "reason", "invalid_credentials",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should track multiple authentication attempts") {
            repeat(5) {
                metrics.recordAuthentication(success = true, reason = null, realmId = "default")
            }
            repeat(3) {
                metrics.recordAuthentication(success = false, reason = "account_locked", realmId = "default")
            }

            val successCounter = registry.counter(
                "kodex.authentication.attempts",
                "success", "true",
                "reason", "none",
                "realm", "default"
            )
            val failureCounter = registry.counter(
                "kodex.authentication.attempts",
                "success", "false",
                "reason", "account_locked",
                "realm", "default"
            )

            successCounter.count() shouldBe 5.0
            failureCounter.count() shouldBe 3.0
        }
    }

    context("Token Operation Metrics") {

        test("should record token issue operation") {
            metrics.recordTokenOperation(
                operation = "issue",
                tokenType = "access",
                success = true,
                realmId = "default"
            )

            val counter = registry.counter(
                "kodex.token.operations",
                "operation", "issue",
                "token_type", "access",
                "success", "true",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should record token refresh operation") {
            metrics.recordTokenOperation(
                operation = "refresh",
                tokenType = "refresh",
                success = true,
                realmId = "default"
            )

            val counter = registry.counter(
                "kodex.token.operations",
                "operation", "refresh",
                "token_type", "refresh",
                "success", "true",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should record failed token operation") {
            metrics.recordTokenOperation(
                operation = "validate",
                tokenType = "access",
                success = false,
                realmId = "default"
            )

            val counter = registry.counter(
                "kodex.token.operations",
                "operation", "validate",
                "token_type", "access",
                "success", "false",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }
    }

    context("Account Lockout Metrics") {

        test("should record account locked event") {
            metrics.recordAccountLockout(locked = true, realmId = "default")

            val counter = registry.counter(
                "kodex.account.lockouts",
                "locked", "true",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should record account unlocked event") {
            metrics.recordAccountLockout(locked = false, realmId = "default")

            val counter = registry.counter(
                "kodex.account.lockouts",
                "locked", "false",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }
    }

    context("Validation Failure Metrics") {

        test("should record validation failure") {
            metrics.recordValidationFailure(
                field = "email",
                reason = "invalid_format",
                realmId = "default"
            )

            val counter = registry.counter(
                "kodex.validation.failures",
                "field", "email",
                "reason", "invalid_format",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should track different validation failures") {
            metrics.recordValidationFailure("email", "invalid_format", "default")
            metrics.recordValidationFailure("password", "too_short", "default")
            metrics.recordValidationFailure("phone", "invalid_format", "default")

            val emailCounter = registry.counter(
                "kodex.validation.failures",
                "field", "email",
                "reason", "invalid_format",
                "realm", "default"
            )
            val passwordCounter = registry.counter(
                "kodex.validation.failures",
                "field", "password",
                "reason", "too_short",
                "realm", "default"
            )

            emailCounter.count() shouldBe 1.0
            passwordCounter.count() shouldBe 1.0
        }
    }

    context("User Operation Metrics") {

        test("should record successful user creation") {
            metrics.recordUserOperation(
                operation = "create",
                success = true,
                realmId = "default"
            )

            val counter = registry.counter(
                "kodex.user.operations",
                "operation", "create",
                "success", "true",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }

        test("should record failed user update") {
            metrics.recordUserOperation(
                operation = "update",
                success = false,
                realmId = "default"
            )

            val counter = registry.counter(
                "kodex.user.operations",
                "operation", "update",
                "success", "false",
                "realm", "default"
            )

            counter.count() shouldBe 1.0
        }
    }

    context("Database Query Metrics") {

        test("should record database query timing") {
            metrics.recordDatabaseQuery(operation = "user_select", durationMs = 150)

            val timer = registry.timer(
                "kodex.database.query.time",
                "operation", "user_select"
            )

            timer.count() shouldBe 1
            timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) shouldBeGreaterThan 0.0
        }

        test("should track multiple query timings") {
            metrics.recordDatabaseQuery("user_select", 100)
            metrics.recordDatabaseQuery("user_select", 200)
            metrics.recordDatabaseQuery("token_insert", 50)

            val selectTimer = registry.timer(
                "kodex.database.query.time",
                "operation", "user_select"
            )
            val insertTimer = registry.timer(
                "kodex.database.query.time",
                "operation", "token_insert"
            )

            selectTimer.count() shouldBe 2
            insertTimer.count() shouldBe 1
        }
    }

    context("Multi-Realm Metrics") {

        test("should track metrics separately per realm") {
            metrics.recordAuthentication(success = true, realmId = "realm1")
            metrics.recordAuthentication(success = true, realmId = "realm2")
            metrics.recordAuthentication(success = true, realmId = "realm1")

            val realm1Counter = registry.counter(
                "kodex.authentication.attempts",
                "success", "true",
                "reason", "none",
                "realm", "realm1"
            )
            val realm2Counter = registry.counter(
                "kodex.authentication.attempts",
                "success", "true",
                "reason", "none",
                "realm", "realm2"
            )

            realm1Counter.count() shouldBe 2.0
            realm2Counter.count() shouldBe 1.0
        }
    }
})
