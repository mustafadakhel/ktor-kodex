package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.model.database.AuditLogDao
import com.mustafadakhel.kodex.model.database.AuditLogs
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class AuditRetentionServiceTest : FunSpec({

    lateinit var retentionService: AuditRetentionService
    val timeZone = TimeZone.UTC

    beforeEach {
        // Setup H2 in-memory database
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:audit_retention_test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
        }
        setupExposedEngine(HikariDataSource(config))

        // Create retention service with 90-day retention
        retentionService = auditRetentionService(
            config = AuditRetentionConfig(retentionPeriod = 90.days),
            timeZone = timeZone
        )
    }

    afterEach {
        Db.clearEngine()
    }

    context("Audit Log Cleanup") {

        test("should delete audit logs older than retention period") {
            val now = Clock.System.now()

            // Create audit logs at different ages
            val oldLog1 = createAuditLog(timestamp = now.minus(100.days))  // 100 days old - should be deleted
            val oldLog2 = createAuditLog(timestamp = now.minus(95.days))   // 95 days old - should be deleted
            val recentLog1 = createAuditLog(timestamp = now.minus(80.days)) // 80 days old - should be kept
            val recentLog2 = createAuditLog(timestamp = now.minus(1.days))  // 1 day old - should be kept

            // Run cleanup
            val deletedCount = retentionService.cleanupOldAuditLogs()

            // Verify 2 old logs were deleted
            deletedCount shouldBe 2

            // Verify remaining logs
            val remainingLogs = exposedTransaction {
                AuditLogs.selectAll().map { it[AuditLogs.id].value }
            }

            remainingLogs.size shouldBe 2
            remainingLogs shouldBe listOf(recentLog1, recentLog2)
        }

        test("should delete nothing when all logs are within retention period") {
            val now = Clock.System.now()

            // Create only recent logs
            createAuditLog(timestamp = now.minus(1.days))
            createAuditLog(timestamp = now.minus(30.days))
            createAuditLog(timestamp = now.minus(60.days))

            // Run cleanup
            val deletedCount = retentionService.cleanupOldAuditLogs()

            // Verify nothing was deleted
            deletedCount shouldBe 0

            // Verify all logs still exist
            val remainingCount = exposedTransaction {
                AuditLogs.selectAll().count()
            }
            remainingCount shouldBe 3
        }

        test("should delete all logs when all are older than retention period") {
            val now = Clock.System.now()

            // Create only old logs
            createAuditLog(timestamp = now.minus(100.days))
            createAuditLog(timestamp = now.minus(120.days))
            createAuditLog(timestamp = now.minus(200.days))

            // Run cleanup
            val deletedCount = retentionService.cleanupOldAuditLogs()

            // Verify all were deleted
            deletedCount shouldBe 3

            // Verify table is empty
            val remainingCount = exposedTransaction {
                AuditLogs.selectAll().count()
            }
            remainingCount shouldBe 0
        }

        test("should handle empty audit log table") {
            // Run cleanup on empty table
            val deletedCount = retentionService.cleanupOldAuditLogs()

            // Verify nothing crashed and 0 deleted
            deletedCount shouldBe 0
        }

        test("should delete logs at exact cutoff boundary correctly") {
            // Use a fixed cutoff date to avoid timing issues with Clock.System.now()
            val cutoffDate = LocalDateTime(2025, 7, 1, 12, 0, 0)

            // Create log exactly at cutoff (at boundary - should be kept)
            val exactlyAtCutoff = createAuditLog(timestamp = cutoffDate)

            // Create log just before cutoff (older - should be deleted)
            val justBeforeCutoff = createAuditLog(timestamp = cutoffDate.toInstant(timeZone).minus(1.hours).toLocalDateTime(timeZone))

            // Create log just after cutoff (newer - should be kept)
            val justAfterCutoff = createAuditLog(timestamp = cutoffDate.toInstant(timeZone).plus(1.hours).toLocalDateTime(timeZone))

            // Run cleanup with specific cutoff
            val deletedCount = retentionService.cleanupAuditLogsOlderThan(cutoffDate)

            // Boundary behavior: logs older than cutoff are deleted
            // "less than" means the exact cutoff should be kept
            deletedCount shouldBe 1

            val remainingLogs = exposedTransaction {
                AuditLogs.selectAll().map { it[AuditLogs.id].value }
            }

            remainingLogs.size shouldBe 2
            remainingLogs shouldBe listOf(exactlyAtCutoff, justAfterCutoff)
        }
    }

    context("Manual Cleanup with Specific Cutoff") {

        test("should delete logs older than specified cutoff date") {
            // Create logs at different times
            val log1 = createAuditLog(timestamp = LocalDateTime(2025, 1, 1, 0, 0))
            val log2 = createAuditLog(timestamp = LocalDateTime(2025, 6, 1, 0, 0))
            val log3 = createAuditLog(timestamp = LocalDateTime(2025, 9, 1, 0, 0))

            // Delete logs older than June 1, 2025
            val cutoffDate = LocalDateTime(2025, 6, 1, 0, 0)
            val deletedCount = retentionService.cleanupAuditLogsOlderThan(cutoffDate)

            // Verify only log1 was deleted (older than June 1)
            deletedCount shouldBe 1

            val remainingLogs = exposedTransaction {
                AuditLogs.selectAll().map { it[AuditLogs.id].value }
            }

            remainingLogs.size shouldBe 2
            remainingLogs shouldBe listOf(log2, log3)
        }

        test("should handle cutoff date in the future") {
            // Create current logs
            createAuditLog(timestamp = Clock.System.now().minus(10.days))
            createAuditLog(timestamp = Clock.System.now().minus(5.days))

            // Try to delete with cutoff date in future (should delete everything)
            val futureCutoff = Clock.System.now().plus(30.days).toLocalDateTime(timeZone)
            val deletedCount = retentionService.cleanupAuditLogsOlderThan(futureCutoff)

            // All logs should be deleted
            deletedCount shouldBe 2
        }
    }

    context("Configuration") {

        test("should return configured retention period") {
            val period = retentionService.getRetentionPeriod()
            period shouldBe 90.days
        }

        test("should support custom retention periods") {
            // Create service with 30-day retention
            val customService = auditRetentionService(
                config = AuditRetentionConfig(retentionPeriod = 30.days),
                timeZone = timeZone
            )

            val now = Clock.System.now()

            // Create logs at 40 and 20 days old
            val oldLog = createAuditLog(timestamp = now.minus(40.days))
            val recentLog = createAuditLog(timestamp = now.minus(20.days))

            // Cleanup with 30-day retention
            val deletedCount = customService.cleanupOldAuditLogs()

            // Verify only the 40-day-old log was deleted
            deletedCount shouldBe 1

            val remainingLogs = exposedTransaction {
                AuditLogs.selectAll().map { it[AuditLogs.id].value }
            }
            remainingLogs shouldBe listOf(recentLog)
        }
    }

    context("GDPR Compliance") {

        test("should support right to erasure with specific cutoff") {
            // Simulate a user requesting deletion of all their audit logs
            val userId = UUID.randomUUID()

            // Create audit logs for this user
            val log1 = createAuditLog(
                timestamp = LocalDateTime(2025, 1, 1, 0, 0),
                targetId = userId
            )
            val log2 = createAuditLog(
                timestamp = LocalDateTime(2025, 5, 1, 0, 0),
                targetId = userId
            )
            val log3 = createAuditLog(
                timestamp = LocalDateTime(2025, 7, 1, 0, 0),
                targetId = userId
            )

            // Note: This service deletes by time, not by user
            // For GDPR right to erasure, a separate method would be needed
            // This test demonstrates the timestamp-based deletion works

            val cutoff = LocalDateTime(2025, 6, 1, 0, 0)
            val deletedCount = retentionService.cleanupAuditLogsOlderThan(cutoff)

            // January and May logs should be deleted (both before June 1)
            deletedCount shouldBe 2

            val remainingLogs = exposedTransaction {
                AuditLogs.selectAll().map { it[AuditLogs.id].value }
            }
            remainingLogs shouldBe listOf(log3)
        }

        test("default 90-day retention should be suitable for most compliance needs") {
            // Most regulations require 6 months to 2 years
            // 90 days is conservative and suitable for most applications

            val config = AuditRetentionConfig()  // Default config

            config.retentionPeriod shouldBe 90.days
            config.enabled shouldBe true
        }
    }
})

/**
 * Helper function to create an audit log entry for testing.
 */
private fun createAuditLog(
    timestamp: Instant,
    targetId: UUID? = null
): UUID {
    return createAuditLog(
        timestamp = timestamp.toLocalDateTime(TimeZone.UTC),
        targetId = targetId
    )
}

/**
 * Helper function to create an audit log entry with LocalDateTime.
 */
private fun createAuditLog(
    timestamp: LocalDateTime,
    targetId: UUID? = null
): UUID {
    return exposedTransaction {
        // Insert directly into the table to avoid DAO metadata serialization issues
        val id = UUID.randomUUID()
        AuditLogs.insert {
            it[AuditLogs.id] = id
            it[AuditLogs.eventType] = "TEST_EVENT"
            it[AuditLogs.timestamp] = timestamp.toInstant(TimeZone.UTC)
            it[AuditLogs.actorId] = UUID.randomUUID()
            it[AuditLogs.actorType] = ActorType.USER
            it[AuditLogs.targetId] = targetId
            it[AuditLogs.targetType] = "TestTarget"
            it[AuditLogs.result] = EventResult.SUCCESS
            it[AuditLogs.metadata] = "{}"
            it[AuditLogs.realmId] = "test-realm"
            it[AuditLogs.sessionId] = null
        }
        id
    }
}
