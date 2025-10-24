package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public interface KodexEvent {
    public val eventId: UUID
    public val timestamp: Instant
    public val realmId: String
    public val eventType: String
    public val schemaVersion: Int get() = 1

    public val requestId: UUID? get() = null
    public val sessionId: UUID? get() = null
    public val sourceIp: String? get() = null
    public val userAgent: String? get() = null
    public val correlationId: UUID? get() = null
    public val causedByEventId: UUID? get() = null
    public val severity: EventSeverity get() = EventSeverity.INFO
    public val durationMs: Long? get() = null
    public val tags: Map<String, String> get() = emptyMap()
}

public enum class EventSeverity {
    INFO,
    WARNING,
    CRITICAL
}
