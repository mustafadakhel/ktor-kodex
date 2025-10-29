package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface SecurityEvent : KodexEvent {

    public data class TokenReplayDetected(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val tokenId: UUID,
        val tokenFamily: UUID,
        val firstUsedAt: String,
        val gracePeriodEnd: String,
        override val requestId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.CRITICAL,
        override val tags: Map<String, String> = emptyMap()
    ) : SecurityEvent {
        override val eventType: String = "SECURITY_VIOLATION"
    }

    public data class RateLimitExceeded(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val identifier: String,
        val limitType: String,
        val threshold: Int,
        val currentCount: Int,
        override val requestId: UUID? = null,
        override val sourceIp: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : SecurityEvent {
        override val eventType: String = "RATE_LIMIT_EXCEEDED"
    }

    public data class AccountLocked(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val reason: String,
        val lockDurationMs: Long?,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : SecurityEvent {
        override val eventType: String = "ACCOUNT_LOCKED"
    }

    public data class AccountUnlocked(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val unlockedBy: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : SecurityEvent {
        override val eventType: String = "ACCOUNT_UNLOCKED"
    }
}
