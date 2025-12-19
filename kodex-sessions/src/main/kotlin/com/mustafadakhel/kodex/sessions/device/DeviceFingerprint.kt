package com.mustafadakhel.kodex.sessions.device

import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import java.security.MessageDigest
import java.util.UUID

public object DeviceFingerprint {
    /**
     * Generate a unique device fingerprint from IP address and user agent.
     * Normalizes user agent to improve stability across browser updates.
     */
    public fun generate(ipAddress: String, userAgent: String?): String {
        val normalizedUA = userAgent?.takeIf { it.isNotBlank() }?.let { normalizeUserAgent(it) } ?: "no-ua"
        val combined = "$ipAddress|$normalizedUA"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Normalizes user agent string by extracting stable browser family and OS.
     * Removes version numbers to improve fingerprint stability across updates.
     */
    private fun normalizeUserAgent(userAgent: String): String {
        val ua = userAgent.lowercase()

        // Extract browser family (ignore specific versions)
        val browser = when {
            ua.contains("edg/") || ua.contains("edge/") -> "edge"
            ua.contains("chrome/") && !ua.contains("edg") -> "chrome"
            ua.contains("firefox/") -> "firefox"
            ua.contains("safari/") && !ua.contains("chrome") && !ua.contains("edg") -> "safari"
            ua.contains("opera") || ua.contains("opr/") -> "opera"
            else -> "other-browser"
        }

        // Extract OS (ignore specific versions)
        val os = when {
            ua.contains("windows nt") -> "windows"
            ua.contains("mac os x") || ua.contains("macos") -> "macos"
            ua.contains("android") -> "android"
            ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") -> "ios"
            ua.contains("linux") && !ua.contains("android") -> "linux"
            else -> "other-os"
        }

        // Extract device type
        val deviceType = when {
            ua.contains("mobile") || ua.contains("iphone") || ua.contains("android") -> "mobile"
            ua.contains("tablet") || ua.contains("ipad") -> "tablet"
            else -> "desktop"
        }

        return "$browser|$os|$deviceType"
    }

    /**
     * Extract a human-readable device name from user agent string.
     */
    public fun extractDeviceName(userAgent: String?): String? {
        if (userAgent == null) return null

        val ua = userAgent.lowercase()

        val browser = when {
            ua.contains("edg/") -> "Edge"
            ua.contains("chrome/") && !ua.contains("edg") -> "Chrome"
            ua.contains("firefox/") -> "Firefox"
            ua.contains("safari/") && !ua.contains("chrome") -> "Safari"
            ua.contains("opera") || ua.contains("opr/") -> "Opera"
            else -> "Browser"
        }

        val os = when {
            ua.contains("windows") -> "Windows"
            // Check Android BEFORE Linux since Android UAs contain "Linux"
            ua.contains("android") -> "Android"
            // Check iOS BEFORE macOS since iOS UAs contain "like Mac OS X"
            ua.contains("iphone") || ua.contains("ipad") -> "iOS"
            ua.contains("mac os") -> "macOS"
            ua.contains("linux") -> "Linux"
            else -> "Unknown OS"
        }

        return "$browser on $os"
    }

    /**
     * Create DeviceInfo from request parameters.
     */
    public fun createDeviceInfo(ipAddress: String, userAgent: String?): DeviceInfo {
        return DeviceInfo(
            fingerprint = generate(ipAddress, userAgent),
            name = extractDeviceName(userAgent),
            ipAddress = ipAddress,
            userAgent = userAgent
        )
    }
}
