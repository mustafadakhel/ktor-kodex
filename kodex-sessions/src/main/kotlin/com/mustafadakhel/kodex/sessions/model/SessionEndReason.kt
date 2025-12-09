package com.mustafadakhel.kodex.sessions.model

/**
 * Standard session termination reasons.
 *
 * Provides type-safe constants for session end reasons to avoid magic strings
 * and ensure consistency across the codebase.
 */
public object SessionEndReason {
    /** Session exceeded maximum concurrent sessions limit and was evicted. */
    public const val MAX_SESSIONS_EXCEEDED: String = "max_sessions_exceeded"

    /** User explicitly revoked the session via logout or session management API. */
    public const val USER_REVOKED: String = "user_revoked"

    /** All sessions were force-terminated (e.g., "logout from all devices"). */
    public const val FORCE_LOGOUT_ALL: String = "force_logout_all"

    /** Session expired naturally after reaching its expiration time. */
    public const val EXPIRED: String = "expired"
}
