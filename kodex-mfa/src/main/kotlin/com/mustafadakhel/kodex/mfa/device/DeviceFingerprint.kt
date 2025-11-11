package com.mustafadakhel.kodex.mfa.device

import java.security.MessageDigest
import java.util.Base64

/**
 * Generates device fingerprints for trusted device recognition using IP address and user agent.
 */
public object DeviceFingerprint {

    /**
     * Generates a device fingerprint from IP address and user agent.
     *
     * @param ipAddress The client IP address (optional)
     * @param userAgent The client user agent string (optional)
     * @return A base64-encoded SHA-256 hash of the device characteristics
     */
    public fun generate(
        ipAddress: String?,
        userAgent: String?
    ): String {
        val components = listOfNotNull(
            ipAddress?.takeIf { it.isNotBlank() },
            userAgent?.takeIf { it.isNotBlank() }
        )

        if (components.isEmpty()) {
            // Fallback for missing data
            return "unknown-device"
        }

        val combined = components.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))

        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
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
