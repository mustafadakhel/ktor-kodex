package com.mustafadakhel.kodex.mfa.device

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Generates device fingerprints for trusted device recognition using IP address and user agent.
 */
public object DeviceFingerprint {

    /**
     * Generates a device fingerprint from IP address and user agent.
     *
     * Normalizes user agent strings to improve stability across browser updates.
     * For missing data, generates a unique identifier to prevent fingerprint collisions.
     *
     * @param ipAddress The client IP address (optional)
     * @param userAgent The client user agent string (optional)
     * @return A base64-encoded SHA-256 hash of the device characteristics
     */
    public fun generate(
        ipAddress: String?,
        userAgent: String?
    ): String {
        val normalizedIP = ipAddress?.takeIf { it.isNotBlank() }
        val normalizedUA = userAgent?.takeIf { it.isNotBlank() }?.let { normalizeUserAgent(it) }

        val components = listOfNotNull(normalizedIP, normalizedUA)

        if (components.isEmpty()) {
            // Generate unique fingerprint for insufficient data to prevent collisions
            // WARNING: This fingerprint won't be stable across requests - device won't be recognized
            val uniqueId = UUID.randomUUID().toString()
            return "insufficient-data-$uniqueId"
        }

        val combined = components.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))

        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
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
     * Extracts a human-readable device name from the user agent.
     *
     * @param userAgent The user agent string
     * @return A simplified device name (e.g., "Chrome on Windows", "Safari on iOS")
     */
    public fun extractDeviceName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return "Unknown Device"

        val browser = when {
            userAgent.contains("Chrome", ignoreCase = true) -> "Chrome"
            userAgent.contains("Firefox", ignoreCase = true) -> "Firefox"
            userAgent.contains("Safari", ignoreCase = true) -> "Safari"
            userAgent.contains("Edge", ignoreCase = true) -> "Edge"
            else -> "Browser"
        }

        val os = when {
            userAgent.contains("Windows", ignoreCase = true) -> "Windows"
            userAgent.contains("Mac OS", ignoreCase = true) -> "macOS"
            userAgent.contains("Android", ignoreCase = true) -> "Android"
            userAgent.contains("Linux", ignoreCase = true) -> "Linux"
            userAgent.contains("iOS", ignoreCase = true) || userAgent.contains("iPhone", ignoreCase = true) -> "iOS"
            else -> "Unknown OS"
        }

        return "$browser on $os"
    }
}
