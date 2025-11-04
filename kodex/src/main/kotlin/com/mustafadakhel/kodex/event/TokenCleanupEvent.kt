package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface TokenCleanupEvent : KodexEvent {

    public data class TokensCleanedUp(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val tokenType: String,
        val tokensRemoved: Int,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : TokenCleanupEvent {
        override val eventType: String = "TOKENS_CLEANED_UP"
    }
}
