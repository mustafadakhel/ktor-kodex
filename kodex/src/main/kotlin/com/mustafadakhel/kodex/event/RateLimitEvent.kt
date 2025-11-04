package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface RateLimitEvent : KodexEvent {

    public data class RateLimitChecked(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val key: String,
        val allowed: Boolean,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : RateLimitEvent {
        override val eventType: String = "RATE_LIMIT_CHECKED"
    }

    public data class RateLimitSizeChanged(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val size: Int,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : RateLimitEvent {
        override val eventType: String = "RATE_LIMIT_SIZE_CHANGED"
    }

    public data class RateLimitCleanupPerformed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val entriesRemoved: Int,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : RateLimitEvent {
        override val eventType: String = "RATE_LIMIT_CLEANUP_PERFORMED"
    }

    public data class RateLimitEvictionPerformed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val entriesEvicted: Int,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : RateLimitEvent {
        override val eventType: String = "RATE_LIMIT_EVICTION_PERFORMED"
    }
}
