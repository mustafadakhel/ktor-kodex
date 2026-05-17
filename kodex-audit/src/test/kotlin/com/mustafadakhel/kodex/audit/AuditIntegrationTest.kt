@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.TimeZone
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.time.Duration.Companion.days

class AuditIntegrationTest : FunSpec({

    lateinit var db: KodexDatabase
    lateinit var auditSchema: AuditSchema
    val timeZone = TimeZone.UTC

    beforeTest {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:audit_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        auditSchema = AuditSchema(core.prefix)
        db = KodexDatabase(
            dataSource = ds,
            dialect = DatabaseDialect.H2,
            core = core,
            extensionSchemas = mapOf(AuditSchema::class to auditSchema)
        )
        db.createSchema()
    }

    context("Event Persistence") {
        test("should persist audit event to database") {
            val provider = DatabaseAuditProvider(db, auditSchema)
            val eventId = UUID.randomUUID()

            val event = AuditEvent(
                eventType = "USER_LOGIN",
                timestamp = CurrentKotlinInstant,
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

            val count = db.transaction {
                select(auditSchema.auditEvents).count()
            }
            count shouldBe 1
        }

        test("should persist multiple events") {
            val provider = DatabaseAuditProvider(db, auditSchema)

            repeat(5) { i ->
                val event = AuditEvent(
                    eventType = "TEST_EVENT_$i",
                    timestamp = CurrentKotlinInstant,
                    actorId = UUID.randomUUID(),
                    result = EventResult.SUCCESS,
                    metadata = emptyMap(),
                    realmId = "test"
                )
                provider.log(event)
            }

            val count = db.transaction {
                select(auditSchema.auditEvents).count()
            }
            count shouldBe 5
        }
    }

    context("Metadata Sanitization - XSS Prevention") {
        test("should escape HTML entities to prevent XSS") {
            val provider = DatabaseAuditProvider(db, auditSchema)

            val maliciousData = mapOf(
                "userName" to "<script>alert('XSS')</script>",
                "comment" to "<img src=x onerror=alert(1)>",
                "data" to "normal & valid <data>"
            )

            val event = AuditEvent(
                eventType = "USER_INPUT",
                timestamp = CurrentKotlinInstant,
                metadata = maliciousData,
                realmId = "test"
            )

            provider.log(event)

            val metadataJson = db.transaction {
                select(auditSchema.auditEvents)
                    .firstOrNull { it[auditSchema.auditEvents.metadata] }!!
            }

            metadataJson shouldContain "&lt;script&gt;"
            metadataJson shouldNotContain "<script>"
        }
    }

    context("Metadata Sanitization - Sensitive Field Redaction") {
        test("should redact password fields") {
            val provider = DatabaseAuditProvider(db, auditSchema)

            val sensitiveData = mapOf(
                "userName" to "john",
                "password" to "secret123",
                "newPassword" to "newsecret456"
            )

            val event = AuditEvent(
                eventType = "PASSWORD_CHANGE",
                timestamp = CurrentKotlinInstant,
                metadata = sensitiveData,
                realmId = "test"
            )

            provider.log(event)

            val metadataJson = db.transaction {
                select(auditSchema.auditEvents)
                    .firstOrNull { it[auditSchema.auditEvents.metadata] }!!
            }

            metadataJson shouldContain "[REDACTED]"
            metadataJson shouldNotContain "secret123"
            metadataJson shouldContain "john"
        }
    }

    context("Retention Cleanup") {
        test("should delete old audit logs") {
            val retentionService = auditRetentionService(
                db = db, schema = auditSchema,
                config = AuditRetentionConfig(retentionPeriod = 30.days),
                realmId = "test", timeZone = timeZone
            )

            val now = CurrentKotlinInstant
            val oldTimestamp = now.minus(45.days)
            val recentTimestamp = now.minus(15.days)

            val provider = DatabaseAuditProvider(db, auditSchema)

            provider.log(AuditEvent(
                eventType = "OLD_EVENT", timestamp = oldTimestamp,
                result = EventResult.SUCCESS, metadata = emptyMap(), realmId = "test"
            ))

            provider.log(AuditEvent(
                eventType = "RECENT_EVENT", timestamp = recentTimestamp,
                result = EventResult.SUCCESS, metadata = emptyMap(), realmId = "test"
            ))

            val deletedCount = retentionService.cleanupOldAuditLogs()
            deletedCount shouldBe 1

            val remaining = db.transaction {
                select(auditSchema.auditEvents).count()
            }
            remaining shouldBe 1
        }
    }

    context("Graceful Error Handling") {
        test("should not throw exception on database errors") {
            val provider = DatabaseAuditProvider(db, auditSchema)

            // Drop the table via raw JDBC to simulate a database error
            db.dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("DROP TABLE ${auditSchema.auditEvents.tableName}") }
            }

            val event = AuditEvent(
                eventType = "TEST", timestamp = CurrentKotlinInstant,
                metadata = emptyMap(), realmId = "test"
            )

            // Should not throw
            provider.log(event)

            // Recreate the table for subsequent tests
            db.dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    for (sql in auditSchema.ddl(DatabaseDialect.H2)) {
                        stmt.execute(sql)
                    }
                }
            }
        }
    }

    context("Actor Types") {
        test("should parse actor type from string") {
            ActorType.fromString("USER") shouldBe ActorType.USER
            ActorType.fromString("ADMIN") shouldBe ActorType.ADMIN
            ActorType.fromString("SYSTEM") shouldBe ActorType.SYSTEM
            ActorType.fromString("ANONYMOUS") shouldBe ActorType.ANONYMOUS
            ActorType.fromString("unknown") shouldBe ActorType.USER
        }
    }

    context("Event Results") {
        test("should parse result from string") {
            EventResult.fromString("SUCCESS") shouldBe EventResult.SUCCESS
            EventResult.fromString("FAILURE") shouldBe EventResult.FAILURE
            EventResult.fromString("PARTIAL_SUCCESS") shouldBe EventResult.PARTIAL_SUCCESS
            EventResult.fromString("PARTIAL") shouldBe EventResult.PARTIAL_SUCCESS
            EventResult.fromString("unknown") shouldBe EventResult.SUCCESS
        }
    }
})
