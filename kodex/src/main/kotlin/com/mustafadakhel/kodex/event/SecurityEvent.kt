package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Events related to security violations and suspicious activity.
 */
public sealed interface SecurityEvent : KodexEvent {

    /**
     * Refresh token replay attack detected.
     */
    public data class TokenReplayDetected(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val tokenId: UUID,
        val tokenFamily: UUID,
        val firstUsedAt: String,
        val gracePeriodEnd: String
    ) : SecurityEvent {
        override val eventType: String = "SECURITY_VIOLATION"
    }
}
