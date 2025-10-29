package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface VerificationEvent : KodexEvent {

    public data class EmailVerificationSent(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val email: String,
        val verificationCode: String? = null,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : VerificationEvent {
        override val eventType: String = "EMAIL_VERIFICATION_SENT"
    }

    public data class EmailVerified(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val email: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : VerificationEvent {
        override val eventType: String = "EMAIL_VERIFIED"
    }

    public data class PhoneVerificationSent(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val phone: String,
        val verificationCode: String? = null,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : VerificationEvent {
        override val eventType: String = "PHONE_VERIFICATION_SENT"
    }

    public data class PhoneVerified(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val phone: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : VerificationEvent {
        override val eventType: String = "PHONE_VERIFIED"
    }

    public data class VerificationFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val verificationType: String,
        val reason: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : VerificationEvent {
        override val eventType: String = "VERIFICATION_FAILED"
    }
}
