@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.jdbc.ConnectionScope
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import com.mustafadakhel.kodex.sessions.schema.SessionSchema
import com.mustafadakhel.kodex.sessions.security.Anomaly
import com.mustafadakhel.kodex.sessions.security.DefaultAnomalyDetector
import com.mustafadakhel.kodex.schema.CoreSchema
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.Duration.Companion.days

class AnomalyDetectorTest : StringSpec({

    val dialect = DatabaseDialect.H2
    val core = CoreSchema("kodex_")
    val schema = SessionSchema(core.prefix)
    val realmId = "test-realm"

    fun withConnection(block: ConnectionScope.() -> Unit) {
        val conn = DriverManager.getConnection("jdbc:h2:mem:anomaly_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        conn.autoCommit = false
        try {
            val cs = ConnectionScope(conn, dialect)

            val ddl = schema.tables().flatMap { listOf(it.createTableDDL(dialect)) + it.createIndexDDL(dialect) }
            conn.createStatement().use { stmt ->
                ddl.forEach { stmt.execute(it) }
            }
            conn.commit()

            cs.block()
            conn.commit()
        } finally {
            conn.close()
        }
    }

    fun ConnectionScope.seedSession(
        repository: SessionRepository,
        userId: UUID,
        fingerprint: String = "seed-fingerprint",
        latitude: Double? = null,
        longitude: Double? = null
    ): Session {
        val sessions = schema.sessions
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(TimeZone.UTC)
        val sessionId = UUID.randomUUID()

        insertInto(sessions) {
            set(sessions.id, sessionId)
            set(sessions.realmId, realmId)
            set(sessions.userId, userId)
            set(sessions.tokenFamily, UUID.randomUUID())
            set(sessions.deviceFingerprint, fingerprint)
            set(sessions.deviceName, "Seed Device")
            set(sessions.ipAddress, "10.0.0.1")
            set(sessions.userAgent, "SeedAgent/1.0")
            set(sessions.location, null)
            set(sessions.latitude, latitude?.toBigDecimal())
            set(sessions.longitude, longitude?.toBigDecimal())
            set(sessions.createdAt, nowLocal)
            set(sessions.lastActivityAt, nowLocal)
            set(sessions.expiresAt, (now + 30.days).toLocalDateTime(TimeZone.UTC))
            set(sessions.status, SessionStatus.ACTIVE)
            set(sessions.revokedAt, null)
            set(sessions.revokedReason, null)
        }

        return with(repository) { findById(sessionId)!! }
    }

    fun createSession(
        id: UUID = UUID.randomUUID(),
        userId: UUID = UUID.randomUUID(),
        fingerprint: String = "test-fingerprint",
        deviceName: String? = "Test Browser",
        ipAddress: String? = "192.168.1.1",
        latitude: Double? = null,
        longitude: Double? = null,
        location: String? = null
    ): Session {
        val now = CurrentKotlinInstant
        return Session(
            id = id,
            realmId = realmId,
            userId = userId,
            tokenFamily = UUID.randomUUID(),
            deviceFingerprint = fingerprint,
            deviceName = deviceName,
            ipAddress = ipAddress,
            userAgent = "TestAgent/1.0",
            location = location,
            latitude = latitude,
            longitude = longitude,
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now + 30.days,
            status = SessionStatus.ACTIVE,
            revokedAt = null,
            revokedReason = null
        )
    }

    fun ConnectionScope.detect(
        detector: DefaultAnomalyDetector,
        userId: UUID,
        session: Session,
        repository: SessionRepository
    ): List<Anomaly> = with(detector) { detectAnomalies(userId, session, repository) }

    "New device should be detected when no previous devices exist" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "new-fingerprint")

        withConnection {
            val anomalies = detect(detector, userId, session, repository)

            anomalies shouldHaveSize 1
            anomalies[0].type shouldBe "new_device"
            anomalies[0].details["deviceFingerprint"] shouldBe "new-fingerprint"
        }
    }

    "New device should be detected when fingerprint not in previous devices" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "new-fingerprint")

        withConnection {
            seedSession(repository, userId, fingerprint = "old-fingerprint-1")
            seedSession(repository, userId, fingerprint = "old-fingerprint-2")

            val anomalies = detect(detector, userId, session, repository)

            anomalies shouldHaveSize 1
            anomalies[0].type shouldBe "new_device"
        }
    }

    "Known device should NOT trigger anomaly" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "known-fingerprint")

        withConnection {
            seedSession(repository, userId, fingerprint = "known-fingerprint")
            seedSession(repository, userId, fingerprint = "other-fingerprint")

            val anomalies = detect(detector, userId, session, repository)

            anomalies.shouldBeEmpty()
        }
    }

    "Device detection should be skipped when disabled" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "new-fingerprint")

        withConnection {
            val anomalies = detect(detector, userId, session, repository)

            anomalies.shouldBeEmpty()
        }
    }

    "New location should be detected when distance exceeds threshold" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()

        // NYC coordinates
        val session = createSession(
            userId = userId,
            latitude = 40.7128,
            longitude = -74.0060,
            location = "New York, USA"
        )

        withConnection {
            // LA coordinates (about 3,940 km from NYC)
            seedSession(repository, userId, latitude = 34.0522, longitude = -118.2437)

            val anomalies = detect(detector, userId, session, repository)

            anomalies shouldHaveSize 1
            anomalies[0].type shouldBe "new_location"
            anomalies[0].details["location"] shouldBe "New York, USA"
        }
    }

    "Location within radius should NOT trigger anomaly" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()

        // Manhattan
        val session = createSession(
            userId = userId,
            latitude = 40.7831,
            longitude = -73.9712,
            location = "Manhattan, NY"
        )

        withConnection {
            // Brooklyn (about 10 km away)
            seedSession(repository, userId, latitude = 40.6782, longitude = -73.9442)

            val anomalies = detect(detector, userId, session, repository)

            anomalies.shouldBeEmpty()
        }
    }

    "No location anomaly when no previous locations exist" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            latitude = 40.7128,
            longitude = -74.0060
        )

        withConnection {
            val anomalies = detect(detector, userId, session, repository)

            anomalies.shouldBeEmpty()
        }
    }

    "Location detection skipped when session has no coordinates" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            latitude = null,
            longitude = null
        )

        withConnection {
            val anomalies = detect(detector, userId, session, repository)

            anomalies.shouldBeEmpty()
        }
    }

    "Location detection should be skipped when disabled" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            latitude = 40.7128,
            longitude = -74.0060
        )

        withConnection {
            val anomalies = detect(detector, userId, session, repository)

            anomalies.shouldBeEmpty()
        }
    }

    "Both new device AND new location should be detected" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = SessionRepository(schema, realmId)
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            fingerprint = "new-fingerprint",
            latitude = 40.7128,
            longitude = -74.0060,
            location = "New York, USA"
        )

        withConnection {
            seedSession(repository, userId, fingerprint = "old-fingerprint", latitude = 34.0522, longitude = -118.2437)

            val anomalies = detect(detector, userId, session, repository)

            anomalies shouldHaveSize 2
            anomalies.map { it.type }.toSet() shouldBe setOf("new_device", "new_location")
        }
    }
})
