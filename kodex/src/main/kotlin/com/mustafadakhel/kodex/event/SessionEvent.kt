package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface SessionEvent : KodexEvent {

    public data class SessionCreated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val kodexSessionId: UUID,
        val userId: UUID,
        val tokenFamily: UUID,
        val deviceFingerprint: String,
        val deviceName: String?,
        val ipAddress: String?,
        val location: String?,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = kodexSessionId,
        override val sourceIp: String? = ipAddress,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : SessionEvent {
        override val eventType: String = "SESSION_CREATED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class SessionRevoked(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val kodexSessionId: UUID,
        val userId: UUID,
        val reason: String,
        val revokedBy: String = "user",
        override val requestId: UUID? = null,
        override val sessionId: UUID? = kodexSessionId,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : SessionEvent {
        override val eventType: String = "SESSION_REVOKED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class SessionActivityUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val kodexSessionId: UUID,
        val userId: UUID,
        val lastActivityAt: Instant,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = kodexSessionId,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : SessionEvent {
        override val eventType: String = "SESSION_ACTIVITY_UPDATED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class SessionAnomalyDetected(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val kodexSessionId: UUID,
        val userId: UUID,
        val anomalyType: String,
        val details: Map<String, String>,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = kodexSessionId,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : SessionEvent {
        override val eventType: String = "SESSION_ANOMALY_DETECTED"
    }

    public data class SessionExpired(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val kodexSessionId: UUID,
        val userId: UUID,
        val expiredAt: Instant,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = kodexSessionId,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : SessionEvent {
        override val eventType: String = "SESSION_EXPIRED"
        override val severity: EventSeverity = EventSeverity.INFO
    }
}
