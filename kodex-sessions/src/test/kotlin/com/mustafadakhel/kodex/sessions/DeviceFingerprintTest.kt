package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.sessions.device.DeviceFingerprint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength

/**
 * Device fingerprint generation tests.
 * Tests consistent hash generation, user agent normalization, and device name extraction.
 */
class DeviceFingerprintTest : StringSpec({

    "Same IP and UserAgent should produce identical fingerprint" {
        val ip = "192.168.1.1"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0"

        val fingerprint1 = DeviceFingerprint.generate(ip, userAgent)
        val fingerprint2 = DeviceFingerprint.generate(ip, userAgent)

        fingerprint1 shouldBe fingerprint2
    }

    "Fingerprint should be SHA-256 hash (64 hex characters)" {
        val fingerprint = DeviceFingerprint.generate("1.2.3.4", "TestAgent/1.0")

        fingerprint shouldHaveLength 64
        fingerprint.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    "Different IP should produce different fingerprint" {
        val userAgent = "Mozilla/5.0 Chrome/120"

        val fingerprint1 = DeviceFingerprint.generate("192.168.1.1", userAgent)
        val fingerprint2 = DeviceFingerprint.generate("192.168.1.2", userAgent)

        fingerprint1 shouldNotBe fingerprint2
    }

    "Different UserAgent should produce different fingerprint" {
        val ip = "192.168.1.1"

        val fingerprint1 = DeviceFingerprint.generate(ip, "Chrome/120")
        val fingerprint2 = DeviceFingerprint.generate(ip, "Firefox/121")

        fingerprint1 shouldNotBe fingerprint2
    }

    "Chrome 120 and Chrome 121 should produce SAME fingerprint (major version stripped)" {
        val ip = "192.168.1.1"
        val chrome120 = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val chrome121 = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

        val fingerprint1 = DeviceFingerprint.generate(ip, chrome120)
        val fingerprint2 = DeviceFingerprint.generate(ip, chrome121)

        // Normalization strips version numbers, so these should match
        fingerprint1 shouldBe fingerprint2
    }

    "Safari iOS vs Safari macOS should produce DIFFERENT fingerprint (OS matters)" {
        val ip = "192.168.1.1"
        val safariMacos = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15"
        val safariIos = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Safari/605.1.15"

        val fingerprint1 = DeviceFingerprint.generate(ip, safariMacos)
        val fingerprint2 = DeviceFingerprint.generate(ip, safariIos)

        fingerprint1 shouldNotBe fingerprint2
    }

    "Chrome Windows vs Chrome macOS should produce DIFFERENT fingerprint" {
        val ip = "192.168.1.1"
        val chromeWindows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0"
        val chromeMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/120.0.0.0"

        val fingerprint1 = DeviceFingerprint.generate(ip, chromeWindows)
        val fingerprint2 = DeviceFingerprint.generate(ip, chromeMac)

        fingerprint1 shouldNotBe fingerprint2
    }

    "Mobile Chrome vs Desktop Chrome should produce DIFFERENT fingerprint" {
        val ip = "192.168.1.1"
        val chromeDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0"
        val chromeMobile = "Mozilla/5.0 (Linux; Android 14) Chrome/120.0.0.0 Mobile"

        val fingerprint1 = DeviceFingerprint.generate(ip, chromeDesktop)
        val fingerprint2 = DeviceFingerprint.generate(ip, chromeMobile)

        fingerprint1 shouldNotBe fingerprint2
    }

    "Null user agent should produce valid fingerprint" {
        val fingerprint = DeviceFingerprint.generate("192.168.1.1", null)

        fingerprint shouldHaveLength 64
    }

    "Empty user agent should be treated like null" {
        val ip = "192.168.1.1"

        val fingerprintNull = DeviceFingerprint.generate(ip, null)
        val fingerprintEmpty = DeviceFingerprint.generate(ip, "")
        val fingerprintBlank = DeviceFingerprint.generate(ip, "   ")

        fingerprintNull shouldBe fingerprintEmpty
        fingerprintNull shouldBe fingerprintBlank
    }

    "Chrome on Windows should extract correct device name" {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)

        deviceName shouldBe "Chrome on Windows"
    }

    "Firefox on Linux should extract correct device name" {
        val userAgent = "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)

        deviceName shouldBe "Firefox on Linux"
    }

    "Safari on macOS should extract correct device name" {
        val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)

        deviceName shouldBe "Safari on macOS"
    }

    "Chrome on Android should extract correct device name" {
        val userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)

        deviceName shouldBe "Chrome on Android"
    }

    "Safari on iOS should extract correct device name" {
        val userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)

        deviceName shouldBe "Safari on iOS"
    }

    "Edge on Windows should extract correct device name" {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"
        val deviceName = DeviceFingerprint.extractDeviceName(userAgent)

        deviceName shouldBe "Edge on Windows"
    }

    "Null user agent should return null device name" {
        val deviceName = DeviceFingerprint.extractDeviceName(null)

        deviceName shouldBe null
    }

    "createDeviceInfo should populate all fields correctly" {
        val ip = "192.168.1.100"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0"

        val deviceInfo = DeviceFingerprint.createDeviceInfo(ip, userAgent)

        deviceInfo.ipAddress shouldBe ip
        deviceInfo.userAgent shouldBe userAgent
        deviceInfo.fingerprint shouldHaveLength 64
        deviceInfo.name shouldBe "Chrome on Windows"
    }

    "createDeviceInfo with null user agent should still work" {
        val ip = "10.0.0.1"

        val deviceInfo = DeviceFingerprint.createDeviceInfo(ip, null)

        deviceInfo.ipAddress shouldBe ip
        deviceInfo.userAgent shouldBe null
        deviceInfo.fingerprint shouldHaveLength 64
        deviceInfo.name shouldBe null
    }
})
