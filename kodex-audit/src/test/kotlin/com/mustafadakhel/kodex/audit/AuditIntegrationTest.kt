package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogDao
import com.mustafadakhel.kodex.audit.database.AuditLogs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Integration tests for Audit Extension.
 *
 * These tests prove the audit logging system works correctly with:
 * - Database persistence
 * - Metadata sanitization (XSS prevention, sensitive data redaction)
 * - Query functionality
 * - Retention cleanup
 * - Graceful error handling
 */
class AuditIntegrationTest : FunSpec({

    // Test database setup
    val database = Database.connect("jdbc:h2:mem:test_audit_integration;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    val timeZone = TimeZone.UTC

    beforeTest {
        // Create audit log table
        transaction(database) {
            SchemaUtils.create(AuditLogs)
        }
    }

    afterTest {
        // Drop audit log table
        transaction(database) {
            SchemaUtils.drop(AuditLogs)
        }
    }

    context("Event Persistence") {
        test("should persist audit event to database") {
            val provider = DatabaseAuditProvider()
            val eventId = UUID.randomUUID()

            val event = AuditEvent(
                eventType = "USER_LOGIN",
                timestamp = Clock.System.now(),
                actorId = eventId,
                actorType = ActorType.USER,
                targetId = null,
                targetType = null,
                result = EventResult.SUCCESS,
                metadata = mapOf("ipAddress" to "192.168.1.1", "userAgent" to "Mozilla/5.0"),
                realmId = "test-realm",
                sessionId = UUID.randomUUID()
            )

            provider.log(event)

            // Verify event was persisted
            val count = transaction(database) {
                AuditLogs.selectAll().count()
            }
            count shouldBe 1

            // Verify event fields
            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            savedEvent.eventType shouldBe "USER_LOGIN"
            savedEvent.actorId shouldBe eventId
            savedEvent.actorType shouldBe ActorType.USER
            savedEvent.result shouldBe EventResult.SUCCESS
            savedEvent.realmId shouldBe "test-realm"
            savedEvent.metadata["ipAddress"] shouldBe "192.168.1.1"
        }

        test("should persist multiple events") {
            val provider = DatabaseAuditProvider()

            repeat(5) { i ->
                val event = AuditEvent(
                    eventType = "TEST_EVENT_$i",
                    timestamp = Clock.System.now(),
                    actorId = UUID.randomUUID(),
                    result = EventResult.SUCCESS,
                    metadata = emptyMap(),
                    realmId = "test"
                )
                provider.log(event)
            }

            val count = transaction(database) {
                AuditLogs.selectAll().count()
            }
            count shouldBe 5
        }

        test("should handle events with null optional fields") {
            val provider = DatabaseAuditProvider()

            val event = AuditEvent(
                eventType = "ANONYMOUS_ACTION",
                timestamp = Clock.System.now(),
                actorId = null, // No actor
                targetId = null, // No target
                targetType = null,
                result = EventResult.FAILURE,
                metadata = emptyMap(),
                realmId = "test",
                sessionId = null // No session
            )

            provider.log(event)

            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            savedEvent.actorId shouldBe null
            savedEvent.targetId shouldBe null
            savedEvent.sessionId shouldBe null
        }
    }

    context("Metadata Sanitization - XSS Prevention") {
        test("should escape HTML entities to prevent XSS") {
            val provider = DatabaseAuditProvider()

            val maliciousData = mapOf(
                "userName" to "<script>alert('XSS')</script>",
                "comment" to "<img src=x onerror=alert(1)>",
                "data" to "normal & valid <data>"
            )

            val event = AuditEvent(
                eventType = "USER_INPUT",
                timestamp = Clock.System.now(),
                metadata = maliciousData,
                realmId = "test"
            )

            provider.log(event)

            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            // Verify HTML entities are escaped
            savedEvent.metadata["userName"] shouldBe "&lt;script&gt;alert(&#x27;XSS&#x27;)&lt;&#x2F;script&gt;"
            savedEvent.metadata["comment"] shouldBe "&lt;img src=x onerror=alert(1)&gt;"
            savedEvent.metadata["data"] shouldBe "normal &amp; valid &lt;data&gt;"

            // Verify the original malicious strings are NOT present in VALUES (checking escaped forms)
            val userNameValue = savedEvent.metadata["userName"] as String
            userNameValue.shouldNotContain("<script>") // Original tag should not be present
            userNameValue.shouldContain("&lt;script&gt;") // Should be escaped
        }

        test("should escape quotes and slashes") {
            val provider = DatabaseAuditProvider()

            val data = mapOf(
                "message" to "He said \"Hello\" and 'Goodbye'",
                "path" to "/usr/bin/test"
            )

            val event = AuditEvent(
                eventType = "TEST",
                timestamp = Clock.System.now(),
                metadata = data,
                realmId = "test"
            )

            provider.log(event)

            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            savedEvent.metadata["message"] shouldBe "He said &quot;Hello&quot; and &#x27;Goodbye&#x27;"
            savedEvent.metadata["path"] shouldBe "&#x2F;usr&#x2F;bin&#x2F;test"
        }
    }

    context("Metadata Sanitization - Sensitive Field Redaction") {
        test("should redact password fields") {
            val provider = DatabaseAuditProvider()

            val sensitiveData = mapOf(
                "userName" to "john",
                "password" to "secret123",
                "newPassword" to "newsecret456",
                "user_password" to "anothersecret"
            )

            val event = AuditEvent(
                eventType = "PASSWORD_CHANGE",
                timestamp = Clock.System.now(),
                metadata = sensitiveData,
                realmId = "test"
            )

            provider.log(event)

            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            // Passwords should be redacted
            savedEvent.metadata["password"] shouldBe "[REDACTED]"
            savedEvent.metadata["newPassword"] shouldBe "[REDACTED]"
            savedEvent.metadata["user_password"] shouldBe "[REDACTED]"

            // Non-sensitive fields should NOT be redacted
            savedEvent.metadata["userName"] shouldBe "john"
        }

        test("should redact token and secret fields") {
            val provider = DatabaseAuditProvider()

            val sensitiveData = mapOf(
                "accessToken" to "abc123",
                "refreshToken" to "xyz789",
                "apiSecret" to "secret",
                "csrfToken" to "csrf123",
                "otpCode" to "123456",
                "normalField" to "public data"
            )

            val event = AuditEvent(
                eventType = "TOKEN_REFRESH",
                timestamp = Clock.System.now(),
                metadata = sensitiveData,
                realmId = "test"
            )

            provider.log(event)

            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            // Sensitive fields should be redacted
            savedEvent.metadata["accessToken"] shouldBe "[REDACTED]"
            savedEvent.metadata["refreshToken"] shouldBe "[REDACTED]"
            savedEvent.metadata["apiSecret"] shouldBe "[REDACTED]"
            savedEvent.metadata["csrfToken"] shouldBe "[REDACTED]"
            savedEvent.metadata["otpCode"] shouldBe "[REDACTED]"

            // Normal fields should remain
            savedEvent.metadata["normalField"] shouldBe "public data"
        }

        test("should handle 'key' fields carefully to avoid false positives") {
            val provider = DatabaseAuditProvider()

            val data = mapOf(
                "apiKey" to "secret123",          // Should redact
                "privateKey" to "key123",         // Should redact
                "keyData" to "data123",           // Should redact
                "keyboard" to "qwerty",           // Should NOT redact (false positive)
                "monkey" to "banana",             // Should NOT redact
                "primaryKey" to "user_id",        // Ambiguous, but should NOT redact (database term)
                "email" to "user@example.com"     // Should NOT redact
            )

            val event = AuditEvent(
                eventType = "TEST",
                timestamp = Clock.System.now(),
                metadata = data,
                realmId = "test"
            )

            provider.log(event)

            val savedEvent = transaction(database) {
                AuditLogDao.all().first()
            }

            // API/private keys should be redacted
            savedEvent.metadata["apiKey"] shouldBe "[REDACTED]"
            savedEvent.metadata["privateKey"] shouldBe "[REDACTED]"
            savedEvent.metadata["keyData"] shouldBe "[REDACTED]"

            // False positives should NOT be redacted
            savedEvent.metadata["keyboard"] shouldBe "qwerty"
            savedEvent.metadata["monkey"] shouldBe "banana"
            savedEvent.metadata["email"] shouldBe "user@example.com"
        }
    }

    context("Query Functionality") {
        test("should query events by event type") {
            val provider = DatabaseAuditProvider()

            // Create different event types
            listOf("USER_LOGIN", "USER_LOGOUT", "USER_LOGIN", "PASSWORD_CHANGE").forEach { eventType ->
                provider.log(AuditEvent(
                    eventType = eventType,
                    timestamp = Clock.System.now(),
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            // Query LOGIN events
            val loginEvents = transaction(database) {
                AuditLogDao.find { AuditLogs.eventType eq "USER_LOGIN" }.toList()
            }

            loginEvents shouldHaveSize 2
            loginEvents.all { it.eventType == "USER_LOGIN" }.shouldBeTrue()
        }

        test("should query events by actor") {
            val provider = DatabaseAuditProvider()
            val actor1 = UUID.randomUUID()
            val actor2 = UUID.randomUUID()

            // Create events for different actors
            repeat(3) {
                provider.log(AuditEvent(
                    eventType = "ACTION",
                    timestamp = Clock.System.now(),
                    actorId = actor1,
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            repeat(2) {
                provider.log(AuditEvent(
                    eventType = "ACTION",
                    timestamp = Clock.System.now(),
                    actorId = actor2,
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            // Query events for actor1
            val actor1Events = transaction(database) {
                AuditLogDao.find { AuditLogs.actorId eq actor1 }.toList()
            }

            actor1Events shouldHaveSize 3
            actor1Events.all { it.actorId == actor1 }.shouldBeTrue()
        }

        test("should query events by realm") {
            val provider = DatabaseAuditProvider()

            // Create events for different realms
            listOf("realm1", "realm2", "realm1", "realm1").forEach { realm ->
                provider.log(AuditEvent(
                    eventType = "EVENT",
                    timestamp = Clock.System.now(),
                    metadata = emptyMap(),
                    realmId = realm
                ))
            }

            // Query realm1 events
            val realm1Events = transaction(database) {
                AuditLogDao.find { AuditLogs.realmId eq "realm1" }.toList()
            }

            realm1Events shouldHaveSize 3
            realm1Events.all { it.realmId == "realm1" }.shouldBeTrue()
        }

        test("should query events by result") {
            val provider = DatabaseAuditProvider()

            // Create events with different results
            repeat(3) {
                provider.log(AuditEvent(
                    eventType = "EVENT",
                    timestamp = Clock.System.now(),
                    result = EventResult.SUCCESS,
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            repeat(2) {
                provider.log(AuditEvent(
                    eventType = "EVENT",
                    timestamp = Clock.System.now(),
                    result = EventResult.FAILURE,
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            // Query failed events
            val failedEvents = transaction(database) {
                AuditLogDao.find { AuditLogs.result eq EventResult.FAILURE }.toList()
            }

            failedEvents shouldHaveSize 2
            failedEvents.all { it.result == EventResult.FAILURE }.shouldBeTrue()
        }
    }

    context("Retention Cleanup") {
        test("should delete old audit logs") {
            val retentionService = DefaultAuditRetentionService(
                retentionPeriod = 30.days,
                timeZone = timeZone
            )

            // Create old and recent events
            val now = Clock.System.now()
            val oldTimestamp = now.minus(45.days) // Older than retention period
            val recentTimestamp = now.minus(15.days) // Within retention period

            // Create events using provider (proper approach)
            val provider = DatabaseAuditProvider()

            provider.log(AuditEvent(
                eventType = "OLD_EVENT",
                timestamp = oldTimestamp,
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = "test"
            ))

            provider.log(AuditEvent(
                eventType = "RECENT_EVENT",
                timestamp = recentTimestamp,
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = "test"
            ))

            // Run cleanup
            val deletedCount = retentionService.cleanupOldAuditLogs()
            deletedCount shouldBe 1

            // Verify only recent event remains
            val remaining = transaction(database) {
                AuditLogDao.all().toList()
            }
            remaining shouldHaveSize 1
            remaining.first().eventType shouldBe "RECENT_EVENT"
        }

        test("should cleanup logs older than specific cutoff date") {
            val retentionService = DefaultAuditRetentionService(
                retentionPeriod = 90.days,
                timeZone = timeZone
            )

            val now = Clock.System.now()
            val cutoffDate = now.minus(60.days).toLocalDateTime(timeZone)

            // Create events using provider
            val provider = DatabaseAuditProvider()

            provider.log(AuditEvent(
                eventType = "OLD",
                timestamp = now.minus(70.days),
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = "test"
            ))

            provider.log(AuditEvent(
                eventType = "NEW",
                timestamp = now.minus(50.days),
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = "test"
            ))

            // Cleanup events older than 60 days ago
            val deletedCount = retentionService.cleanupAuditLogsOlderThan(cutoffDate)
            deletedCount shouldBe 1

            val remaining = transaction(database) {
                AuditLogDao.all().toList()
            }
            remaining shouldHaveSize 1
            remaining.first().eventType shouldBe "NEW"
        }

        test("should return retention period") {
            val retentionService = DefaultAuditRetentionService(
                retentionPeriod = 90.days,
                timeZone = timeZone
            )

            retentionService.getRetentionPeriod() shouldBe 90.days
        }
    }

    context("Graceful Error Handling") {
        test("should not throw exception on database errors") {
            // This test verifies that audit failures don't break main operations
            val provider = DatabaseAuditProvider()

            // Close the database connection to simulate failure
            transaction(database) {
                SchemaUtils.drop(AuditLogs)
            }

            val event = AuditEvent(
                eventType = "TEST",
                timestamp = Clock.System.now(),
                metadata = emptyMap(),
                realmId = "test"
            )

            // Should not throw - errors are caught and logged
            provider.log(event)

            // Recreate table for cleanup
            transaction(database) {
                SchemaUtils.create(AuditLogs)
            }
        }
    }

    context("Actor Types") {
        test("should support all actor types") {
            val provider = DatabaseAuditProvider()

            ActorType.values().forEach { actorType ->
                provider.log(AuditEvent(
                    eventType = "TEST",
                    timestamp = Clock.System.now(),
                    actorType = actorType,
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            val events = transaction(database) {
                AuditLogDao.all().toList()
            }

            events shouldHaveSize 4
            events.map { it.actorType }.toSet() shouldBe ActorType.values().toSet()
        }

        test("should parse actor type from string") {
            ActorType.fromString("USER") shouldBe ActorType.USER
            ActorType.fromString("ADMIN") shouldBe ActorType.ADMIN
            ActorType.fromString("SYSTEM") shouldBe ActorType.SYSTEM
            ActorType.fromString("ANONYMOUS") shouldBe ActorType.ANONYMOUS
            ActorType.fromString("unknown") shouldBe ActorType.USER // Default
        }
    }

    context("Event Results") {
        test("should support all result types") {
            val provider = DatabaseAuditProvider()

            EventResult.values().forEach { result ->
                provider.log(AuditEvent(
                    eventType = "TEST",
                    timestamp = Clock.System.now(),
                    result = result,
                    metadata = emptyMap(),
                    realmId = "test"
                ))
            }

            val events = transaction(database) {
                AuditLogDao.all().toList()
            }

            events shouldHaveSize 3
            events.map { it.result }.toSet() shouldBe EventResult.values().toSet()
        }

        test("should parse result from string") {
            EventResult.fromString("SUCCESS") shouldBe EventResult.SUCCESS
            EventResult.fromString("FAILURE") shouldBe EventResult.FAILURE
            EventResult.fromString("PARTIAL_SUCCESS") shouldBe EventResult.PARTIAL_SUCCESS
            EventResult.fromString("PARTIAL") shouldBe EventResult.PARTIAL_SUCCESS
            EventResult.fromString("unknown") shouldBe EventResult.SUCCESS // Default
        }
    }
})
