package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.sessions.device.DeviceFingerprint
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength

/**
 * Basic unit tests for Session Management components.
 *
 * TODO: Full integration test suite should include:
 * - Session creation on login (via TokenEvent.Issued)
 * - Session listing for authenticated users
 * - Concurrent session limit enforcement
 * - Session revocation and logout
 * - Activity tracking on token refresh (via TokenEvent.Refreshed)
 * - Session history archival
 * - Anomaly detection (new device/location)
 * - GeoLocation integration
 * - Cleanup service functionality
 */
class SessionBasicTest : FunSpec({

    test("device fingerprint generation produces consistent hashes") {
        val ip = "192.168.1.1"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

        val fingerprint1 = DeviceFingerprint.generate(ip, userAgent)
        val fingerprint2 = DeviceFingerprint.generate(ip, userAgent)

        // SHA-256 produces 64 hex characters
        fingerprint1 shouldHaveLength 64

        // Same inputs should produce same fingerprint
        fingerprint1 shouldBe fingerprint2
    }

    test("device name extraction from user agent") {
        // Test with Chrome on Windows
        val chromeAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        val chromeName = DeviceFingerprint.extractDeviceName(chromeAgent)
        chromeName shouldBe "Chrome on Windows"
    }

    test("session status enum") {
        SessionStatus.ACTIVE.name shouldBe "ACTIVE"
        SessionStatus.EXPIRED.name shouldBe "EXPIRED"
        SessionStatus.REVOKED.name shouldBe "REVOKED"
    }
})
