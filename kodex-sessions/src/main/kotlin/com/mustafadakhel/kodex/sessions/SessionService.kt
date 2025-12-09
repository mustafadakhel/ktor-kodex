package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionEndReason
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionHistoryPage
import java.util.UUID

public interface SessionService {
    /**
     * List all active sessions for a user.
     */
    public suspend fun listActiveSessions(userId: UUID): List<Session>

    /**
     * Get a specific session by ID.
     */
    public suspend fun getSession(sessionId: UUID): Session?

    /**
     * Get a session by token family UUID.
     */
    public suspend fun getSessionByTokenFamily(tokenFamily: UUID): Session?

    /**
     * Create a new session.
     */
    public suspend fun createSession(
        userId: UUID,
        tokenFamily: UUID,
        deviceInfo: DeviceInfo,
        expiresAt: kotlinx.datetime.Instant
    ): Session

    /**
     * Revoke a specific session.
     */
    public suspend fun revokeSession(sessionId: UUID, reason: String = SessionEndReason.USER_REVOKED)

    /**
     * Revoke all sessions for a user, optionally except the current one.
     */
    public suspend fun revokeAllSessions(userId: UUID, exceptSessionId: UUID? = null)

    /**
     * Update last activity time and extend expiration for a session.
     * This implements a sliding window expiration where active sessions stay alive.
     */
    public suspend fun updateActivity(tokenFamily: UUID, extendExpirationBy: kotlin.time.Duration)

    /**
     * Get session history for a user.
     */
    public suspend fun getSessionHistory(userId: UUID, limit: Int = 50): List<SessionHistoryEntry>

    /**
     * Get paginated session history for a user.
     */
    public suspend fun getSessionHistoryPage(userId: UUID, limit: Int = 50, offset: Int = 0): SessionHistoryPage

    /**
     * Archive expired sessions to history.
     */
    public suspend fun archiveExpiredSessions(): Int

    /**
     * Clean up old session history.
     */
    public suspend fun cleanupOldHistory(): Int
}
