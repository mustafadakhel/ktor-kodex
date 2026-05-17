@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.SessionEvent
import com.mustafadakhel.kodex.jdbc.ConnectionScope
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionEndReason
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionHistoryPage
import com.mustafadakhel.kodex.sessions.security.AnomalyDetector
import com.mustafadakhel.kodex.sessions.security.GeoLocationService
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.util.UUID

internal class DefaultSessionService(
    private val db: KodexDatabase,
    private val repository: SessionRepository,
    private val config: SessionConfig,
    private val eventBus: EventBus,
    private val anomalyDetector: AnomalyDetector?,
    private val geoLocationService: GeoLocationService?
) : SessionService {

    override suspend fun listActiveSessions(userId: UUID): List<Session> = db.transaction {
        with(repository) { findActiveByUserId(userId) }
    }

    override suspend fun getSession(sessionId: UUID): Session? = db.transaction {
        with(repository) { findById(sessionId) }
    }

    override suspend fun getSessionByTokenFamily(tokenFamily: UUID): Session? = db.transaction {
        with(repository) { findByTokenFamily(tokenFamily) }
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
        val (session, revokedEvent, anomalies) = db.suspendTransaction {
            val session = with(repository) {
                create(
                    userId = userId,
                    tokenFamily = tokenFamily,
                    deviceInfo = deviceInfo,
                    location = location?.displayName,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    expiresAt = expiresAt,
                    now = now
                )
            }

            val revokedEvent = enforceConcurrentSessionLimit(userId, excludeSessionId = session.id)

            val anomalies = if (anomalyDetector != null && config.anomalyDetection.enabled) {
                with(anomalyDetector) { detectAnomalies(userId, session, repository) }
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

    private fun ConnectionScope.enforceConcurrentSessionLimit(
        userId: UUID,
        excludeSessionId: UUID? = null
    ): SessionRevokedEvent? {
        val activeCount = with(repository) { countActiveByUserIdForUpdate(userId) }
        if (activeCount > config.maxConcurrentSessions) {
            val oldestSessionId = with(repository) { findOldestActiveSessionId(userId, excludeSessionId = excludeSessionId) }
            if (oldestSessionId != null) {
                val now = CurrentKotlinInstant
                with(repository) { revoke(oldestSessionId, SessionEndReason.MAX_SESSIONS_EXCEEDED, now) }

                val session = with(repository) { findById(oldestSessionId) }
                if (session != null) {
                    with(repository) { archiveToHistory(session, SessionEndReason.MAX_SESSIONS_EXCEEDED, now) }
                    with(repository) { deleteSession(oldestSessionId) }
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
        val session = db.transaction {
            val session = with(repository) { findById(sessionId) }

            if (session != null) {
                with(repository) {
                    revoke(sessionId, reason, now)
                    archiveToHistory(session, reason, now)
                    deleteSession(sessionId)
                }
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
        val sessionsToRevoke = db.transaction {
            val sessionsToRevoke = with(repository) {
                if (exceptSessionId != null) {
                    findActiveByUserId(userId).filter { it.id != exceptSessionId }
                } else {
                    findActiveByUserId(userId)
                }
            }

            with(repository) {
                revokeAllForUser(userId, exceptSessionId, SessionEndReason.FORCE_LOGOUT_ALL, now)

                if (sessionsToRevoke.isNotEmpty()) {
                    archiveSessionsToHistory(sessionsToRevoke, SessionEndReason.FORCE_LOGOUT_ALL, now)
                    deleteSessions(sessionsToRevoke.map { it.id })
                }
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
        val session = db.transaction {
            val updated = with(repository) { updateActivity(tokenFamily, now, newExpiresAt) }

            if (updated > 0) {
                with(repository) { findByTokenFamily(tokenFamily) }
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

    override suspend fun getSessionHistory(userId: UUID, limit: Int): List<SessionHistoryEntry> = db.transaction {
        val clampedLimit = limit.coerceIn(1, 100)
        with(repository) { findHistoryByUserId(userId, clampedLimit) }
    }

    override suspend fun getSessionHistoryPage(userId: UUID, limit: Int, offset: Int): SessionHistoryPage = db.transaction {
        val clampedLimit = limit.coerceIn(1, 100)
        val totalCount = with(repository) { countHistoryByUserId(userId) }
        val entries = with(repository) { findHistoryByUserId(userId, clampedLimit, offset) }
        SessionHistoryPage.create(entries, totalCount, offset, clampedLimit)
    }

    override suspend fun archiveExpiredSessions(): Int {
        val now = CurrentKotlinInstant
        val expiredSessions = db.transaction {
            with(repository) {
                markExpired(now)

                val expiredSessions = findExpiredSessions()

                if (expiredSessions.isNotEmpty()) {
                    archiveSessionsToHistory(expiredSessions, SessionEndReason.EXPIRED, null)
                    deleteSessions(expiredSessions.map { it.id })
                }

                expiredSessions
            }
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

    override suspend fun cleanupOldHistory(): Int = db.transaction {
        val cutoffTime = CurrentKotlinInstant - config.sessionHistoryRetention
        with(repository) { deleteOldHistory(cutoffTime) }
    }
}
