@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.SessionEndReason
import com.mustafadakhel.kodex.sessions.schema.SessionSchema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.Clock
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DefaultSessionServiceIntegrationTest : FunSpec({

    val eventBus = mockk<EventBus>(relaxed = true)
    val realmId = "test-realm"
    val userId = UUID.randomUUID()

    val ds = JdbcDataSource().apply {
        setUrl("jdbc:h2:mem:session_test_${System.nanoTime()};DB_CLOSE_DELAY=-1")
    }
    val core = CoreSchema("")
    val sessionSchema = SessionSchema("")
    val db = KodexDatabase(
        dataSource = ds,
        dialect = DatabaseDialect.H2,
        core = core,
        extensionSchemas = mapOf(SessionSchema::class to sessionSchema)
    )

    val repository = SessionRepository(sessionSchema, realmId)
    val config = SessionConfig().apply {
        maxConcurrentSessions = 3
        sessionExpiration = 30.days
        sessionHistoryRetention = 90.days
    }

    val sessionService = DefaultSessionService(
        db = db,
        repository = repository,
        config = config,
        eventBus = eventBus,
        anomalyDetector = null,
        geoLocationService = null
    )

    fun seedUser(id: UUID = UUID.randomUUID(), email: String = "$id@test.com"): UUID {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO USERS (ID, REALM_ID, EMAIL, PASSWORD_HASH, STATUS, CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, realmId)
                ps.setString(3, email)
                ps.setString(4, "hash")
                ps.setString(5, "ACTIVE")
                ps.executeUpdate()
            }
        }
        return id
    }

    fun deviceInfo(suffix: Int) = DeviceInfo(
        fingerprint = "fp-$suffix",
        name = "Device $suffix",
        ipAddress = "192.168.1.$suffix",
        userAgent = "TestAgent/$suffix"
    )

    beforeSpec {
        db.createSchema()
        seedUser(userId, "test@test.com")
    }

    afterSpec {
        db.close()
    }

    test("createSession stores and returns session") {
        val tokenFamily = UUID.randomUUID()
        val expiresAt = Clock.System.now() + 1.hours

        val session = sessionService.createSession(userId, tokenFamily, deviceInfo(1), expiresAt)

        session.userId shouldBe userId
        session.tokenFamily shouldBe tokenFamily
        session.deviceFingerprint shouldBe "fp-1"
    }

    test("getSession returns existing session") {
        val tokenFamily = UUID.randomUUID()
        val created = sessionService.createSession(userId, tokenFamily, deviceInfo(2), Clock.System.now() + 1.hours)

        val fetched = sessionService.getSession(created.id)
        fetched.shouldNotBeNull()
        fetched.id shouldBe created.id
    }

    test("getSessionByTokenFamily finds session") {
        val tokenFamily = UUID.randomUUID()
        sessionService.createSession(userId, tokenFamily, deviceInfo(3), Clock.System.now() + 1.hours)

        val found = sessionService.getSessionByTokenFamily(tokenFamily)
        found.shouldNotBeNull()
        found.tokenFamily shouldBe tokenFamily
    }

    test("concurrent session limit revokes oldest when exceeded") {
        val testUser = seedUser()
        val expiresAt = Clock.System.now() + 1.hours

        repeat(3) { i ->
            sessionService.createSession(testUser, UUID.randomUUID(), deviceInfo(20 + i), expiresAt)
        }

        // 4th session triggers eviction of oldest
        sessionService.createSession(testUser, UUID.randomUUID(), deviceInfo(23), expiresAt)

        val active = sessionService.listActiveSessions(testUser)
        active.size shouldBe 3
    }

    test("revokeSession removes session and archives to history") {
        val testUser = seedUser()
        val session = sessionService.createSession(
            testUser, UUID.randomUUID(), deviceInfo(30), Clock.System.now() + 1.hours
        )

        sessionService.revokeSession(session.id, SessionEndReason.USER_REVOKED)

        sessionService.getSession(session.id).shouldBeNull()

        val history = sessionService.getSessionHistory(testUser)
        history.any { it.sessionId == session.id } shouldBe true
    }

    test("revokeAllSessions revokes all except the excluded one") {
        val testUser = seedUser()
        val sessions = (1..3).map { i ->
            sessionService.createSession(
                testUser, UUID.randomUUID(), deviceInfo(40 + i), Clock.System.now() + 1.hours
            )
        }

        val keepSessionId = sessions[1].id
        sessionService.revokeAllSessions(testUser, exceptSessionId = keepSessionId)

        val active = sessionService.listActiveSessions(testUser)
        active shouldHaveSize 1
        active.first().id shouldBe keepSessionId
    }

    test("updateActivity extends session expiration") {
        val testUser = seedUser()
        val tokenFamily = UUID.randomUUID()
        val initialExpires = Clock.System.now() + 1.hours
        sessionService.createSession(testUser, tokenFamily, deviceInfo(50), initialExpires)

        sessionService.updateActivity(tokenFamily, 2.hours)

        val updated = sessionService.getSessionByTokenFamily(tokenFamily)
        updated.shouldNotBeNull()
        (updated.expiresAt > initialExpires) shouldBe true
    }

    test("archiveExpiredSessions removes expired sessions") {
        val testUser = seedUser()
        val expiredTime = Clock.System.now() - 1.minutes
        sessionService.createSession(testUser, UUID.randomUUID(), deviceInfo(60), expiredTime)

        val archivedCount = sessionService.archiveExpiredSessions()
        archivedCount shouldBe 1

        sessionService.listActiveSessions(testUser) shouldHaveSize 0
    }

    test("getSessionHistoryPage returns paginated results") {
        val testUser = seedUser()

        repeat(5) { i ->
            val session = sessionService.createSession(
                testUser, UUID.randomUUID(), deviceInfo(70 + i), Clock.System.now() + 1.hours
            )
            sessionService.revokeSession(session.id)
        }

        val page1 = sessionService.getSessionHistoryPage(testUser, limit = 2, offset = 0)
        page1.entries shouldHaveSize 2
        page1.totalCount shouldBe 5
        page1.hasMore shouldBe true

        val page3 = sessionService.getSessionHistoryPage(testUser, limit = 2, offset = 4)
        page3.entries shouldHaveSize 1
        page3.hasMore shouldBe false
    }
})
