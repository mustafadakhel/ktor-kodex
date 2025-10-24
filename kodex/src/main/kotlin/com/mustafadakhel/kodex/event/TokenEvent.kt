package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface TokenEvent : KodexEvent {
    public val userId: UUID

    public data class Issued(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val tokenId: UUID,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val causedByEventId: UUID? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : TokenEvent {
        override val eventType: String = "TOKEN_ISSUED"
    }

    public data class Refreshed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val oldTokenId: UUID,
        val newTokenId: UUID,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : TokenEvent {
        override val eventType: String = "TOKEN_REFRESHED"
    }

    public data class RefreshFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val reason: String,
        val failureReason: FailureReason = FailureReason.INVALID_TOKEN,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : TokenEvent {
        override val eventType: String = "TOKEN_REFRESH_FAILED"
    }

    public data class Revoked(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val revokedCount: Int,
        val tokenIds: List<UUID> = emptyList(),
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : TokenEvent {
        override val eventType: String = "TOKEN_REVOKED"
    }

    public data class VerifyFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val reason: String,
        val failureReason: FailureReason = FailureReason.INVALID_TOKEN,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.INFO,
        override val tags: Map<String, String> = emptyMap()
    ) : TokenEvent {
        override val eventType: String = "TOKEN_VERIFY_FAILED"
        override val userId: UUID get() = UUID(0, 0)
    }
}
