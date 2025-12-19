package com.mustafadakhel.kodex.sessions.model

import kotlinx.datetime.Instant
import java.util.UUID

public data class Session(
    val id: UUID,
    val realmId: String,
    val userId: UUID,
    val tokenFamily: UUID,
    val deviceFingerprint: String,
    val deviceName: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val location: String?,
    val latitude: Double?,
    val longitude: Double?,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
    val status: SessionStatus,
    val revokedAt: Instant?,
    val revokedReason: String?
)

public data class SessionHistoryEntry(
    val id: UUID,
    val realmId: String,
    val userId: UUID,
    val sessionId: UUID,
    val deviceName: String?,
    val ipAddress: String?,
    val location: String?,
    val loginAt: Instant,
    val logoutAt: Instant?,
    val endReason: String
)

public data class SessionHistoryPage(
    val entries: List<SessionHistoryEntry>,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
) {
    public companion object {
        public fun create(entries: List<SessionHistoryEntry>, totalCount: Long, offset: Int, limit: Int): SessionHistoryPage {
            return SessionHistoryPage(
                entries = entries,
                totalCount = totalCount,
                offset = offset,
                limit = limit,
                hasMore = (offset + entries.size) < totalCount
            )
        }
    }
}

public data class DeviceInfo(
    val fingerprint: String,
    val name: String?,
    val ipAddress: String,
    val userAgent: String?
)

public data class GeoLocation(
    val city: String?,
    val country: String,
    val latitude: Double,
    val longitude: Double
) {
    public val displayName: String get() = if (city != null) "$city, $country" else country
}
