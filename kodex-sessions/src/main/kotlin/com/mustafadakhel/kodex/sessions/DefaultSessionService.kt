package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.SessionEvent
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionEndReason
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import com.mustafadakhel.kodex.sessions.security.AnomalyDetector
import com.mustafadakhel.kodex.sessions.security.GeoLocationService
import com.mustafadakhel.kodex.util.kodexSuspendedTransaction
import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.util.UUID

internal class DefaultSessionService(
    private val repository: SessionRepository,
    private val config: SessionConfig,
    private val eventBus: EventBus,
    private val anomalyDetector: AnomalyDetector?,
    private val geoLocationService: GeoLocationService?
) : SessionService {

    override suspend fun listActiveSessions(userId: UUID): List<Session> = kodexTransaction {
        repository.findActiveByUserId(userId)
    }

    override suspend fun getSession(sessionId: UUID): Session? = kodexTransaction {
        repository.findById(sessionId)
    }

    override suspend fun getSessionByTokenFamily(tokenFamily: UUID): Session? = kodexTransaction {
        repository.findByTokenFamily(tokenFamily)
    }

    override suspend fun createSession(
        userId: UUID,
        tokenFamily: UUID,
        deviceInfo: DeviceInfo,
        expiresAt: Instant
    ): Session {
        val location = if (geoLocationService != null && config.geoLocation.enabled) {
            geoLocationService.resolveLocation(deviceInfo.ipAddress)
        } else {
            null
        }

        val now = CurrentKotlinInstant
        val (session, revokedEvent, anomalies) = kodexSuspendedTransaction {
            // Create session FIRST to avoid race condition
            val session = repository.create(
                userId = userId,
                tokenFamily = tokenFamily,
                deviceInfo = deviceInfo,
                location = location?.displayName,
                latitude = location?.latitude,
                longitude = location?.longitude,
                expiresAt = expiresAt,
                now = now
            )

            // Then enforce limit - if we exceeded, evict oldest (not the one we just created)
            val revokedEvent = enforceConcurrentSessionLimit(userId, excludeSessionId = session.id)

            // Detect anomalies while still in transaction context
            val anomalies = if (anomalyDetector != null && config.anomalyDetection.enabled) {
                anomalyDetector.detectAnomalies(userId, session, repository)
            } else {
                emptyList()
            }

            Triple(session, revokedEvent, anomalies)
        }

        if (revokedEvent != null) {
            eventBus.publish(
                SessionEvent.SessionRevoked(
                    eventId = UUID.randomUUID(),
                    timestamp = revokedEvent.now,
                    realmId = repository.realmId,
                    kodexSessionId = revokedEvent.sessionId,
                    userId = revokedEvent.userId,
                    reason = SessionEndReason.MAX_SESSIONS_EXCEEDED,
                    revokedBy = "system"
                )
            )
        }

        eventBus.publish(
            SessionEvent.SessionCreated(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = repository.realmId,
                kodexSessionId = session.id,
                userId = userId,
                tokenFamily = tokenFamily,
                deviceFingerprint = deviceInfo.fingerprint,
                deviceName = deviceInfo.name,
                ipAddress = deviceInfo.ipAddress,
                location = location?.displayName
            )
        )

        // Publish anomaly events after session creation
        anomalies.forEach { anomaly ->
            eventBus.publish(
                SessionEvent.SessionAnomalyDetected(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = repository.realmId,
                    kodexSessionId = session.id,
                    userId = userId,
                    anomalyType = anomaly.type,
                    details = anomaly.details
                )
            )
        }

        return session
    }

    private fun enforceConcurrentSessionLimit(userId: UUID, excludeSessionId: UUID? = null): SessionRevokedEvent? {
        // Use row-level locking to prevent race conditions when checking session count
        // This ensures that concurrent logins don't bypass the maxConcurrentSessions limit
        val activeCount = repository.countActiveByUserIdForUpdate(userId)
        if (activeCount > config.maxConcurrentSessions) {
            val oldestSessionId = repository.findOldestActiveSessionId(userId, excludeSessionId = excludeSessionId)
            if (oldestSessionId != null) {
                val now = CurrentKotlinInstant
                repository.revoke(oldestSessionId, SessionEndReason.MAX_SESSIONS_EXCEEDED, now)

                val session = repository.findById(oldestSessionId)
                if (session != null) {
                    repository.archiveToHistory(session, SessionEndReason.MAX_SESSIONS_EXCEEDED, now)
                    repository.deleteSession(oldestSessionId)
                }

                return SessionRevokedEvent(
                    sessionId = oldestSessionId,
                    userId = userId,
                    now = now
                )
            }
        }
        return null
    }

    private data class SessionRevokedEvent(
        val sessionId: UUID,
        val userId: UUID,
        val now: Instant
    )

    override suspend fun revokeSession(sessionId: UUID, reason: String) {
        val now = CurrentKotlinInstant
        val session = kodexTransaction {
            val session = repository.findById(sessionId)

            if (session != null) {
                repository.revoke(sessionId, reason, now)
                repository.archiveToHistory(session, reason, now)
                repository.deleteSession(sessionId)
            }

            session
        }

        if (session != null) {
            eventBus.publish(
                SessionEvent.SessionRevoked(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = repository.realmId,
                    kodexSessionId = sessionId,
                    userId = session.userId,
                    reason = reason,
                    revokedBy = "user"
                )
            )
        }
    }

    override suspend fun revokeAllSessions(userId: UUID, exceptSessionId: UUID?) {
        val now = CurrentKotlinInstant
        val sessionsToRevoke = kodexTransaction {
            val sessionsToRevoke = if (exceptSessionId != null) {
                repository.findActiveByUserId(userId).filter { it.id != exceptSessionId }
            } else {
                repository.findActiveByUserId(userId)
            }

            repository.revokeAllForUser(userId, exceptSessionId, SessionEndReason.FORCE_LOGOUT_ALL, now)

            // Batch archive and delete to avoid N+1 queries
            if (sessionsToRevoke.isNotEmpty()) {
                repository.archiveSessionsToHistory(sessionsToRevoke, SessionEndReason.FORCE_LOGOUT_ALL, now)
                repository.deleteSessions(sessionsToRevoke.map { it.id })
            }

            sessionsToRevoke
        }

        sessionsToRevoke.forEach { session ->
            eventBus.publish(
                SessionEvent.SessionRevoked(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = repository.realmId,
                    kodexSessionId = session.id,
                    userId = userId,
                    reason = SessionEndReason.FORCE_LOGOUT_ALL,
                    revokedBy = "user"
                )
            )
        }
    }

    override suspend fun updateActivity(tokenFamily: UUID, extendExpirationBy: kotlin.time.Duration) {
        val now = CurrentKotlinInstant
        val newExpiresAt = now + extendExpirationBy
        val session = kodexTransaction {
            val updated = repository.updateActivity(tokenFamily, now, newExpiresAt)

            if (updated > 0) {
                repository.findByTokenFamily(tokenFamily)
            } else {
                null
            }
        }

        if (session != null) {
            eventBus.publish(
                SessionEvent.SessionActivityUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = repository.realmId,
                    kodexSessionId = session.id,
                    userId = session.userId,
                    lastActivityAt = now
                )
            )
        }
    }

    override suspend fun getSessionHistory(userId: UUID, limit: Int): List<SessionHistoryEntry> = kodexTransaction {
        repository.findHistoryByUserId(userId, limit)
    }

    override suspend fun getSessionHistoryPage(userId: UUID, limit: Int, offset: Int): com.mustafadakhel.kodex.sessions.model.SessionHistoryPage = kodexTransaction {
        val totalCount = repository.countHistoryByUserId(userId)
        val entries = repository.findHistoryByUserId(userId, limit, offset)
        com.mustafadakhel.kodex.sessions.model.SessionHistoryPage.create(entries, totalCount, offset, limit)
    }

    override suspend fun archiveExpiredSessions(): Int {
        val now = CurrentKotlinInstant
        val expiredSessions = kodexTransaction {
            repository.markExpired(now)

            val expiredSessions = repository.findExpiredSessions()

            // Batch archive and delete to avoid N+1 queries
            if (expiredSessions.isNotEmpty()) {
                repository.archiveSessionsToHistory(expiredSessions, SessionEndReason.EXPIRED, null)
                repository.deleteSessions(expiredSessions.map { it.id })
            }

            expiredSessions
        }

        expiredSessions.forEach { session ->
            eventBus.publish(
                SessionEvent.SessionExpired(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = repository.realmId,
                    kodexSessionId = session.id,
                    userId = session.userId,
                    expiredAt = session.expiresAt
                )
            )
        }

        return expiredSessions.size
    }

    override suspend fun cleanupOldHistory(): Int = kodexTransaction {
        val cutoffTime = CurrentKotlinInstant - config.sessionHistoryRetention
        repository.deleteOldHistory(cutoffTime)
    }
}
