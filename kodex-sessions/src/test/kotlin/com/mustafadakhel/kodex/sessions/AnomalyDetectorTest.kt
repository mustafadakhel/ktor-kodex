package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import com.mustafadakhel.kodex.sessions.security.DefaultAnomalyDetector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Session anomaly detection tests.
 * Tests new device detection and new location detection.
 */
class AnomalyDetectorTest : StringSpec({

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
            realmId = "test-realm",
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

    "New device should be detected when no previous devices exist" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "new-fingerprint")

        every { repository.findPreviousDevices(userId, excludeSessionId = session.id) } returns emptyList()

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies shouldHaveSize 1
        anomalies[0].type shouldBe "new_device"
        anomalies[0].details["deviceFingerprint"] shouldBe "new-fingerprint"
    }

    "New device should be detected when fingerprint not in previous devices" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "new-fingerprint")

        every { repository.findPreviousDevices(userId, excludeSessionId = session.id) } returns listOf(
            "old-fingerprint-1",
            "old-fingerprint-2"
        )

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies shouldHaveSize 1
        anomalies[0].type shouldBe "new_device"
    }

    "Known device should NOT trigger anomaly" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "known-fingerprint")

        every { repository.findPreviousDevices(userId, excludeSessionId = session.id) } returns listOf(
            "known-fingerprint",
            "other-fingerprint"
        )

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies.shouldBeEmpty()
    }

    "Device detection should be skipped when disabled" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()
        val session = createSession(userId = userId, fingerprint = "new-fingerprint")

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies.shouldBeEmpty()
    }

    "New location should be detected when distance exceeds threshold" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()

        // NYC coordinates
        val session = createSession(
            userId = userId,
            latitude = 40.7128,
            longitude = -74.0060,
            location = "New York, USA"
        )

        // LA coordinates (about 3,940 km from NYC)
        every { repository.findPreviousLocations(userId, limit = 10, excludeSessionId = session.id) } returns listOf(
            34.0522 to -118.2437 // LA
        )

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies shouldHaveSize 1
        anomalies[0].type shouldBe "new_location"
        anomalies[0].details["location"] shouldBe "New York, USA"
    }

    "Location within radius should NOT trigger anomaly" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()

        // Manhattan
        val session = createSession(
            userId = userId,
            latitude = 40.7831,
            longitude = -73.9712,
            location = "Manhattan, NY"
        )

        // Brooklyn (about 10 km away)
        every { repository.findPreviousLocations(userId, limit = 10, excludeSessionId = session.id) } returns listOf(
            40.6782 to -73.9442 // Brooklyn
        )

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies.shouldBeEmpty()
    }

    "No location anomaly when no previous locations exist" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            latitude = 40.7128,
            longitude = -74.0060
        )

        every { repository.findPreviousLocations(userId, limit = 10, excludeSessionId = session.id) } returns emptyList()

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies.shouldBeEmpty()
    }

    "Location detection skipped when session has no coordinates" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            latitude = null,
            longitude = null
        )

        // Should not even call findPreviousLocations
        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies.shouldBeEmpty()
    }

    "Location detection should be skipped when disabled" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = false
            detectNewLocation = false
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            latitude = 40.7128,
            longitude = -74.0060
        )

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies.shouldBeEmpty()
    }

    "Both new device AND new location should be detected" {
        val config = AnomalyDetectionConfig().apply {
            enabled = true
            detectNewDevice = true
            detectNewLocation = true
            locationRadiusKm = 100.0
        }
        val detector = DefaultAnomalyDetector(config)
        val repository = mockk<SessionRepository>()
        val userId = UUID.randomUUID()

        val session = createSession(
            userId = userId,
            fingerprint = "new-fingerprint",
            latitude = 40.7128,
            longitude = -74.0060,
            location = "New York, USA"
        )

        every { repository.findPreviousDevices(userId, excludeSessionId = session.id) } returns listOf("old-fingerprint")
        every { repository.findPreviousLocations(userId, limit = 10, excludeSessionId = session.id) } returns listOf(
            34.0522 to -118.2437 // LA
        )

        val anomalies = runBlocking { detector.detectAnomalies(userId, session, repository) }

        anomalies shouldHaveSize 2
        anomalies.map { it.type }.toSet() shouldBe setOf("new_device", "new_location")
    }
})
