package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.SessionEvent
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import com.mustafadakhel.kodex.sessions.security.AnomalyDetector
import com.mustafadakhel.kodex.sessions.security.GeoLocationService
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
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

        val (session, revokedEvent) = kodexTransaction {
            // Create session FIRST to avoid race condition
            val session = repository.create(
                userId = userId,
                tokenFamily = tokenFamily,
                deviceInfo = deviceInfo,
                location = location?.displayName,
                latitude = location?.latitude,
                longitude = location?.longitude,
                expiresAt = expiresAt
            )

            // Then enforce limit - if we exceeded, evict oldest (not the one we just created)
            val revokedEvent = enforceConcurrentSessionLimit(userId, excludeSessionId = session.id)

            session to revokedEvent
        }

        if (revokedEvent != null) {
            eventBus.publish(
                SessionEvent.SessionRevoked(
                    eventId = UUID.randomUUID(),
                    timestamp = revokedEvent.now,
                    realmId = repository.realmId,
                    kodexSessionId = revokedEvent.sessionId,
                    userId = revokedEvent.userId,
                    reason = "max_sessions_exceeded",
                    revokedBy = "system"
                )
            )
        }

        eventBus.publish(
            SessionEvent.SessionCreated(
                eventId = UUID.randomUUID(),
                timestamp = Clock.System.now(),
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

        if (anomalyDetector != null && config.anomalyDetection.enabled) {
            val anomalies = anomalyDetector.detectAnomalies(userId, session, repository)
            anomalies.forEach { anomaly ->
                eventBus.publish(
                    SessionEvent.SessionAnomalyDetected(
                        eventId = UUID.randomUUID(),
                        timestamp = Clock.System.now(),
                        realmId = repository.realmId,
                        kodexSessionId = session.id,
                        userId = userId,
                        anomalyType = anomaly.type,
                        details = anomaly.details
                    )
                )
            }
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
                val now = Clock.System.now()
                repository.revoke(oldestSessionId, "max_sessions_exceeded", now)

                val session = repository.findById(oldestSessionId)
                if (session != null) {
                    repository.archiveToHistory(session, "max_sessions_exceeded", now)
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
        val now = Clock.System.now()
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
        val now = Clock.System.now()
        val sessionsToRevoke = kodexTransaction {
            val sessionsToRevoke = if (exceptSessionId != null) {
                repository.findActiveByUserId(userId).filter { it.id != exceptSessionId }
            } else {
                repository.findActiveByUserId(userId)
            }

            repository.revokeAllForUser(userId, exceptSessionId, "force_logout_all", now)

            sessionsToRevoke.forEach { session ->
                repository.archiveToHistory(session, "force_logout_all", now)
                repository.deleteSession(session.id)
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
                    reason = "force_logout_all",
                    revokedBy = "user"
                )
            )
        }
    }

    override suspend fun updateActivity(tokenFamily: UUID, extendExpirationBy: kotlin.time.Duration) {
        val now = Clock.System.now()
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

    override suspend fun archiveExpiredSessions(): Int {
        val now = Clock.System.now()
        val expiredSessions = kodexTransaction {
            repository.markExpired(now)

            val expiredSessions = repository.findExpiredSessions()
            expiredSessions.forEach { session ->
                repository.archiveToHistory(session, "expired", null)
                repository.deleteSession(session.id)
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
        val cutoffTime = Clock.System.now() - config.sessionHistoryRetention
        repository.deleteOldHistory(cutoffTime)
    }
}
