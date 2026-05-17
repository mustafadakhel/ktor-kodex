@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.device.DeviceFingerprint
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.schema.SessionSchema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.datetime.Clock
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Smoke test verifying the session creation path that TokenIssuedSubscriber would trigger.
 * Tests the same code path as the event subscriber: DeviceFingerprint → createSession → verify in DB.
 */
class SessionEventWiringTest : FunSpec({

    test("login event path creates session with correct device fingerprint") {
        val realmId = "event-test"
        val userId = UUID.randomUUID()
        val tokenFamily = UUID.randomUUID()
        val sourceIp = "10.0.0.1"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0"

        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:event_wire_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("")
        val sessionSchema = SessionSchema("")
        val db = KodexDatabase(
            dataSource = ds,
            dialect = DatabaseDialect.H2,
            core = core,
            extensionSchemas = mapOf(SessionSchema::class to sessionSchema)
        )
        db.createSchema()

        // Seed user
        ds.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO USERS (ID, REALM_ID, EMAIL, PASSWORD_HASH, STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, realmId)
                ps.setString(3, "event@test.com")
                ps.setString(4, "hash")
                ps.setString(5, "ACTIVE")
                ps.executeUpdate()
            }
        }

        val repository = SessionRepository(sessionSchema, realmId)
        val eventBus = mockk<EventBus>(relaxed = true)
        val config = SessionConfig().apply {
            maxConcurrentSessions = 10
            sessionExpiration = 30.days
        }

        val sessionService = DefaultSessionService(
            db = db,
            repository = repository,
            config = config,
            eventBus = eventBus,
            anomalyDetector = null,
            geoLocationService = null
        )

        // Simulate exactly what TokenIssuedSubscriber does:
        val fingerprint = DeviceFingerprint.generate(sourceIp, userAgent)
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)
        val deviceInfo = DeviceInfo(
            fingerprint = fingerprint,
            name = deviceName,
            ipAddress = sourceIp,
            userAgent = userAgent
        )
        val expiresAt = Clock.System.now() + config.sessionExpiration

        val session = sessionService.createSession(userId, tokenFamily, deviceInfo, expiresAt)

        // Verify session was created correctly
        session.userId shouldBe userId
        session.tokenFamily shouldBe tokenFamily
        session.deviceFingerprint shouldBe fingerprint

        // Verify it's persisted and queryable
        val active = sessionService.listActiveSessions(userId)
        active.shouldNotBeEmpty()
        active.first().tokenFamily shouldBe tokenFamily

        // Verify lookup by token family works (used for activity updates on refresh)
        val byFamily = sessionService.getSessionByTokenFamily(tokenFamily)
        byFamily shouldBe session

        db.close()
    }
})
