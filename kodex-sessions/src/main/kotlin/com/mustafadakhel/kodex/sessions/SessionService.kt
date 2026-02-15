package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionEndReason
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionHistoryPage
import java.util.UUID
import kotlinx.datetime.Instant
import kotlin.time.Duration

public interface SessionService {
    public suspend fun listActiveSessions(userId: UUID): List<Session>

    public suspend fun getSession(sessionId: UUID): Session?

    public suspend fun getSessionByTokenFamily(tokenFamily: UUID): Session?

    public suspend fun createSession(
        userId: UUID,
        tokenFamily: UUID,
        deviceInfo: DeviceInfo,
        expiresAt: Instant
    ): Session

    public suspend fun revokeSession(sessionId: UUID, reason: String = SessionEndReason.USER_REVOKED)

    public suspend fun revokeAllSessions(userId: UUID, exceptSessionId: UUID? = null)

    /**
     * Update last activity time and extend expiration for a session.
     * This implements a sliding window expiration where active sessions stay alive.
     */
    public suspend fun updateActivity(tokenFamily: UUID, extendExpirationBy: Duration)

    public suspend fun getSessionHistory(userId: UUID, limit: Int = 50): List<SessionHistoryEntry>

    public suspend fun getSessionHistoryPage(userId: UUID, limit: Int = 50, offset: Int = 0): SessionHistoryPage

    public suspend fun archiveExpiredSessions(): Int

    public suspend fun cleanupOldHistory(): Int
}
