package com.mustafadakhel.kodex.observability

import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class HealthCheckTest : FunSpec({

    beforeEach {
        // Setup H2 in-memory database for testing
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:health_check_test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
        }
        setupExposedEngine(HikariDataSource(config))
    }

    afterEach {
        Db.clearEngine()
    }

    context("Database Health Check") {

        test("should return UP when database is accessible") {
            val result = KodexHealth.checkDatabase()

            result.status shouldBe HealthStatus.UP
            result.isHealthy() shouldBe true
            result.error shouldBe null
            result.details shouldNotBe null
            result.details["description"] shouldBe "Database connection successful"
            result.details["responseTimeMs"] shouldNotBe null
        }

        test("should include response time in health check") {
            val result = KodexHealth.checkDatabase()

            result.details.containsKey("responseTimeMs") shouldBe true
            val responseTime = result.details["responseTimeMs"] as? Long
            responseTime shouldNotBe null
            (responseTime!! >= 0) shouldBe true
        }
    }

    context("Overall Health Check") {

        test("should return UP when all components are healthy") {
            val result = KodexHealth.checkOverall()

            result.status shouldBe HealthStatus.UP
            result.isHealthy() shouldBe true
            result.error shouldBe null
            result.details["database"] shouldBe "UP"
            result.details["description"] shouldBe "All systems operational"
        }

        test("should include component statuses in overall health") {
            val result = KodexHealth.checkOverall()

            result.details.containsKey("database") shouldBe true
            result.details["database"] shouldBe "UP"
        }
    }

    context("Health Check Result") {

        test("UP status should be healthy") {
            val result = HealthCheckResult(status = HealthStatus.UP)
            result.isHealthy() shouldBe true
        }

        test("DEGRADED status should be healthy") {
            val result = HealthCheckResult(status = HealthStatus.DEGRADED)
            result.isHealthy() shouldBe true
        }

        test("DOWN status should not be healthy") {
            val result = HealthCheckResult(status = HealthStatus.DOWN)
            result.isHealthy() shouldBe false
        }

        test("should include error message when failed") {
            val result = HealthCheckResult(
                status = HealthStatus.DOWN,
                error = "Connection timeout"
            )

            result.error shouldBe "Connection timeout"
            result.isHealthy() shouldBe false
        }

        test("should include details map") {
            val result = HealthCheckResult(
                status = HealthStatus.UP,
                details = mapOf(
                    "component" to "database",
                    "version" to "1.0.0"
                )
            )

            result.details["component"] shouldBe "database"
            result.details["version"] shouldBe "1.0.0"
        }
    }
})
