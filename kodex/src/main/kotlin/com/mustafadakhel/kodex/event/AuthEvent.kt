package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Events related to authentication and authorization.
 */
public sealed interface AuthEvent : KodexEvent {

    /**
     * User successfully logged in.
     */
    public data class LoginSuccess(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val identifier: String,
        val method: String
    ) : AuthEvent {
        override val eventType: String = "LOGIN_SUCCESS"
    }

    /**
     * Login attempt failed.
     */
    public data class LoginFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val identifier: String,
        val reason: String,
        val method: String,
        val userId: UUID? = null,
        val actorType: String = "ANONYMOUS"
    ) : AuthEvent {
        override val eventType: String = "LOGIN_FAILED"
    }

    /**
     * User changed their password.
     */
    public data class PasswordChanged(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val actorId: UUID
    ) : AuthEvent {
        override val eventType: String = "PASSWORD_CHANGED"
    }

    /**
     * Password change attempt failed.
     */
    public data class PasswordChangeFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val actorId: UUID,
        val reason: String
    ) : AuthEvent {
        override val eventType: String = "PASSWORD_CHANGE_FAILED"
    }

    /**
     * Administrator reset user password.
     */
    public data class PasswordReset(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val actorType: String = "ADMIN"
    ) : AuthEvent {
        override val eventType: String = "PASSWORD_RESET"
    }
}
