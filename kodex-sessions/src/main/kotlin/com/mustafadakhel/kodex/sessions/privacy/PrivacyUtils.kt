package com.mustafadakhel.kodex.sessions.privacy

import kotlin.math.roundToInt

private const val MAX_USER_AGENT_LENGTH = 256
private const val GEOLOCATION_PRECISION_FACTOR = 100.0

/**
 * Anonymize an IP address by zeroing the host portion.
 * IPv4: replaces last octet with 0 (e.g., "192.168.1.123" -> "192.168.1.0")
 * IPv6: replaces last 80 bits (5 groups) with zeros (e.g., "2001:db8:85a3:1234:5678:8a2e:370:7334" -> "2001:db8:85a3:0:0:0:0:0")
 */
internal fun anonymizeIp(ip: String): String {
    if (ip.contains(':')) return anonymizeIpv6(ip)
    return anonymizeIpv4(ip)
}

private fun anonymizeIpv4(ip: String): String {
    val parts = ip.split('.')
    if (parts.size != 4) return ip
    return "${parts[0]}.${parts[1]}.${parts[2]}.0"
}

private fun anonymizeIpv6(ip: String): String {
    // Expand :: shorthand to full form first
    val expanded = expandIpv6(ip)
    val groups = expanded.split(':')
    if (groups.size != 8) return ip
    // Keep first 3 groups (48 bits), zero out last 5 groups (80 bits)
    val kept = groups.take(3)
    val zeroed = List(5) { "0" }
    return (kept + zeroed).joinToString(":")
}

private fun expandIpv6(ip: String): String {
    // Handle :: expansion
    if (!ip.contains("::")) return ip

    val parts = ip.split("::")
    val left = if (parts[0].isEmpty()) emptyList() else parts[0].split(':')
    val right = if (parts.size < 2 || parts[1].isEmpty()) emptyList() else parts[1].split(':')
    val missingGroups = 8 - left.size - right.size
    val middle = List(missingGroups) { "0" }
    return (left + middle + right).joinToString(":")
}

/**
 * Reduce geolocation precision to ~1km by rounding to 2 decimal places.
 */
internal fun reduceGeoPrecision(coordinate: Double): Double {
    return (coordinate * GEOLOCATION_PRECISION_FACTOR).roundToInt() / GEOLOCATION_PRECISION_FACTOR
}

/**
 * Truncate user agent string to a maximum length to avoid storing excessive data.
 */
internal fun truncateUserAgent(userAgent: String?): String? {
    if (userAgent == null) return null
    return if (userAgent.length > MAX_USER_AGENT_LENGTH) {
        userAgent.substring(0, MAX_USER_AGENT_LENGTH)
    } else {
        userAgent
    }
}
