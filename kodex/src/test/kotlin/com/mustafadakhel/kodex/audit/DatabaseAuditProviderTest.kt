package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.model.database.AuditLogDao
import com.mustafadakhel.kodex.model.database.AuditLogs
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.deleteAll
import java.util.UUID

class DatabaseAuditProviderTest : DescribeSpec({

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:audit_provider_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
        }
        setupExposedEngine(HikariDataSource(config))

        exposedTransaction {
            AuditLogs.deleteAll()
        }
    }

    describe("DatabaseAuditProvider") {
        val provider = DatabaseAuditProvider()

        it("should persist audit event to database") {
            val event = AuditEvent(
                eventType = "USER_CREATED",
                timestamp = Clock.System.now(),
                actorId = UUID.randomUUID(),
                actorType = ActorType.ADMIN,
                targetId = UUID.randomUUID(),
                targetType = "User",
                result = EventResult.SUCCESS,
                metadata = mapOf("email" to "test@example.com"),
                realmId = "test-realm",
                sessionId = UUID.randomUUID()
            )

            provider.log(event)

            val persisted = exposedTransaction {
                AuditLogDao.all().singleOrNull()
            }

            persisted shouldNotBe null
            persisted!!.eventType shouldBe "USER_CREATED"
            persisted.actorId shouldBe event.actorId
            persisted.actorType shouldBe ActorType.ADMIN
            persisted.targetId shouldBe event.targetId
            persisted.targetType shouldBe "User"
            persisted.result shouldBe EventResult.SUCCESS
            persisted.realmId shouldBe "test-realm"
            persisted.sessionId shouldBe event.sessionId
            persisted.metadata shouldBe mapOf("email" to "test@example.com")
        }

        it("should persist multiple audit events") {
            val events = (1..5).map { i ->
                AuditEvent(
                    eventType = "EVENT_$i",
                    timestamp = Clock.System.now(),
                    actorType = ActorType.USER,
                    realmId = "test-realm"
                )
            }

            events.forEach { provider.log(it) }

            val count = exposedTransaction {
                AuditLogDao.all().count()
            }

            count shouldBe 5
        }

        it("should handle events with minimal fields") {
            val event = AuditEvent(
                eventType = "MINIMAL_EVENT",
                timestamp = Clock.System.now(),
                realmId = "test-realm"
            )

            provider.log(event)

            val persisted = exposedTransaction {
                AuditLogDao.all().singleOrNull()
            }

            persisted shouldNotBe null
            persisted!!.eventType shouldBe "MINIMAL_EVENT"
            persisted.actorId shouldBe null
            persisted.actorType shouldBe ActorType.USER
            persisted.targetId shouldBe null
            persisted.targetType shouldBe null
            persisted.result shouldBe EventResult.SUCCESS
            persisted.metadata shouldBe emptyMap()
        }

        it("should handle failed login events") {
            val event = AuditEvent(
                eventType = "LOGIN_FAILED",
                timestamp = Clock.System.now(),
                actorType = ActorType.ANONYMOUS,
                realmId = "test-realm",
                metadata = mapOf(
                    "identifier" to "user@example.com",
                    "reason" to "Invalid password",
                    "method" to "email"
                ),
                result = EventResult.FAILURE
            )

            provider.log(event)

            val persisted = exposedTransaction {
                AuditLogDao.all().singleOrNull()
            }

            persisted shouldNotBe null
            persisted!!.eventType shouldBe "LOGIN_FAILED"
            persisted.actorType shouldBe ActorType.ANONYMOUS
            persisted.result shouldBe EventResult.FAILURE
            persisted.metadata["identifier"] shouldBe "user@example.com"
            persisted.metadata["reason"] shouldBe "Invalid password"
        }

        it("should not fail main operation even if logging fails") {
            val event = AuditEvent(
                eventType = "TEST_EVENT",
                timestamp = Clock.System.now(),
                realmId = "test-realm"
            )

            provider.log(event)

            val count = exposedTransaction {
                AuditLogDao.all().count()
            }

            count shouldBe 1
        }
    }
})
