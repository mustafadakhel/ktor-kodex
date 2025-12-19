package com.mustafadakhel.kodex.mfa.session

import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public data class PendingMfaSession(
    val sessionId: String,
    val userId: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val ipAddress: String?,
    val userAgent: String?,
    val verified: Boolean = false,
    val verifiedAt: Instant? = null
)

public class MfaSessionStore(
    private val sessionExpiration: Duration = 5.minutes,
    private val maxActiveSessions: Int = 3
) {
    private val sessions = ConcurrentHashMap<String, PendingMfaSession>()
    private val userSessions = ConcurrentHashMap<UUID, MutableSet<String>>()

    public fun createSession(
        userId: UUID,
        ipAddress: String?,
        userAgent: String?
    ): PendingMfaSession {
        cleanupExpiredSessions()
        enforceLimits(userId)

        val sessionId = UUID.randomUUID().toString()
        val now = CurrentKotlinInstant
        val session = PendingMfaSession(
            sessionId = sessionId,
            userId = userId,
            createdAt = now,
            expiresAt = now + sessionExpiration,
            ipAddress = ipAddress,
            userAgent = userAgent
        )

        sessions[sessionId] = session
        userSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(sessionId)

        return session
    }

    public fun getSession(sessionId: String): PendingMfaSession? {
        val session = sessions[sessionId] ?: return null
        return if (session.isExpired()) {
            removeSession(sessionId)
            null
        } else {
            session
        }
    }

    public fun removeSession(sessionId: String) {
        val session = sessions.remove(sessionId)
        session?.let { userSessions[it.userId]?.remove(sessionId) }
    }

    public fun markAsVerified(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        if (session.isExpired()) {
            removeSession(sessionId)
            return false
        }

        val verifiedSession = session.copy(
            verified = true,
            verifiedAt = CurrentKotlinInstant
        )
        sessions[sessionId] = verifiedSession
        return true
    }

    private fun enforceLimits(userId: UUID) {
        val userSessionIds = userSessions[userId] ?: return
        if (userSessionIds.size >= maxActiveSessions) {
            val oldestSession = userSessionIds
                .mapNotNull { sessions[it] }
                .minByOrNull { it.createdAt }

            oldestSession?.let { removeSession(it.sessionId) }
        }
    }

    public fun cleanupExpiredSessions(): Int {
        val expiredSessionIds = sessions.entries
            .filter { (_, session) -> session.isExpired() }
            .map { it.key }

        expiredSessionIds.forEach { removeSession(it) }
        return expiredSessionIds.size
    }

    private fun PendingMfaSession.isExpired(): Boolean =
        CurrentKotlinInstant > expiresAt
}
