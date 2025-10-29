package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface AuthEvent : KodexEvent {

    public data class LoginSuccess(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val identifier: String,
        val method: String,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : AuthEvent {
        override val eventType: String = "LOGIN_SUCCESS"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class LoginFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val identifier: String,
        val reason: String,
        val method: String,
        val userId: UUID? = null,
        val actorType: String = "ANONYMOUS",
        val failureReason: FailureReason = FailureReason.INVALID_CREDENTIALS,
        val riskScore: Int = 0,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : AuthEvent {
        override val eventType: String = "LOGIN_FAILED"
    }

    public data class PasswordChanged(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val actorId: UUID,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : AuthEvent {
        override val eventType: String = "PASSWORD_CHANGED"
    }

    public data class PasswordChangeFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val actorId: UUID,
        val reason: String,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : AuthEvent {
        override val eventType: String = "PASSWORD_CHANGE_FAILED"
    }

    public data class PasswordReset(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val actorType: String = "ADMIN",
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : AuthEvent {
        override val eventType: String = "PASSWORD_RESET"
    }
}
