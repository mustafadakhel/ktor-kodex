package com.mustafadakhel.kodex.observability

import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.exposed.sql.Database
import java.util.UUID

class HealthCheckTest : FunSpec({

    lateinit var db: KodexDatabase

    beforeEach {
        val database = Database.connect(
            "jdbc:h2:mem:health_check_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val core = CoreSchema("test_")
        db = KodexDatabase(database, core)
        db.createSchema()
    }

    context("Database Health Check") {

        test("should return UP when database is accessible") {
            val health = KodexHealth(db)
            val result = health.checkDatabase()

            result.status shouldBe HealthStatus.UP
            result.isHealthy shouldBe true
            result.error shouldBe null
            result.details shouldNotBe null
            result.details["description"] shouldBe "Database connection successful"
            result.details["responseTimeMs"] shouldNotBe null
        }

        test("should include response time in health check") {
            val health = KodexHealth(db)
            val result = health.checkDatabase()

            result.details.containsKey("responseTimeMs") shouldBe true
            val responseTime = result.details["responseTimeMs"] as? Long
            responseTime shouldNotBe null
            (responseTime!! >= 0) shouldBe true
        }
    }

    context("Overall Health Check") {

        test("should return UP when all components are healthy") {
            val health = KodexHealth(db)
            val result = health.checkOverall()

            result.status shouldBe HealthStatus.UP
            result.isHealthy shouldBe true
            result.error shouldBe null
            result.details["database"] shouldBe "UP"
            result.details["description"] shouldBe "All systems operational"
        }

        test("should include component statuses in overall health") {
            val health = KodexHealth(db)
            val result = health.checkOverall()

            result.details.containsKey("database") shouldBe true
            result.details["database"] shouldBe "UP"
        }
    }

    context("Health Check Result") {

        test("UP status should be healthy") {
            val result = HealthCheckResult(status = HealthStatus.UP)
            result.isHealthy shouldBe true
        }

        test("DEGRADED status should be healthy") {
            val result = HealthCheckResult(status = HealthStatus.DEGRADED)
            result.isHealthy shouldBe true
        }

        test("DOWN status should not be healthy") {
            val result = HealthCheckResult(status = HealthStatus.DOWN)
            result.isHealthy shouldBe false
        }

        test("should include error message when failed") {
            val result = HealthCheckResult(
                status = HealthStatus.DOWN,
                error = "Connection timeout"
            )

            result.error shouldBe "Connection timeout"
            result.isHealthy shouldBe false
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
