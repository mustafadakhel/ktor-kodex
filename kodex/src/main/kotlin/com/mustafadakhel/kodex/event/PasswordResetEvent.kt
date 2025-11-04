package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface PasswordResetEvent : KodexEvent {

    public data class PasswordResetInitiated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID?,
        val contactType: String,
        val contactValue: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : PasswordResetEvent {
        override val eventType: String = "PASSWORD_RESET_INITIATED"
    }

    public data class PasswordResetInitiationFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val contactType: String,
        val contactValue: String,
        val reason: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : PasswordResetEvent {
        override val eventType: String = "PASSWORD_RESET_INITIATION_FAILED"
    }

    public data class PasswordResetTokenVerified(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : PasswordResetEvent {
        override val eventType: String = "PASSWORD_RESET_TOKEN_VERIFIED"
    }

    public data class PasswordResetTokenVerificationFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val reason: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : PasswordResetEvent {
        override val eventType: String = "PASSWORD_RESET_TOKEN_VERIFICATION_FAILED"
    }

    public data class PasswordResetCompleted(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : PasswordResetEvent {
        override val eventType: String = "PASSWORD_RESET_COMPLETED"
    }

    public data class PasswordResetCompletionFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val reason: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : PasswordResetEvent {
        override val eventType: String = "PASSWORD_RESET_COMPLETION_FAILED"
    }
}
