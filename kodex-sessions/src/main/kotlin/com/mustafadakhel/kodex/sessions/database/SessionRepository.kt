@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.sessions.database

import com.mustafadakhel.kodex.jdbc.ConnectionScope
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.Row
import com.mustafadakhel.kodex.jdbc.SortOrder
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.inList
import com.mustafadakhel.kodex.jdbc.isNotNull
import com.mustafadakhel.kodex.jdbc.less
import com.mustafadakhel.kodex.jdbc.neq
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import com.mustafadakhel.kodex.sessions.schema.SessionSchema
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

internal class SessionRepository(
    private val schema: SessionSchema,
    internal val realmId: String
) {
    private val sessions = schema.sessions
    private val history = schema.sessionHistory

    public fun ConnectionScope.create(
        userId: UUID,
        tokenFamily: UUID,
        deviceInfo: DeviceInfo,
        location: String?,
        latitude: Double?,
        longitude: Double?,
        expiresAt: Instant,
        now: Instant
    ): Session {
        val sessionId = UUID.randomUUID()
        val nowLocal = now.toLocalDateTime(TimeZone.UTC)

        insertInto(sessions) {
            set(sessions.id, sessionId)
            set(sessions.realmId, this@SessionRepository.realmId)
            set(sessions.userId, userId)
            set(sessions.tokenFamily, tokenFamily)
            set(sessions.deviceFingerprint, deviceInfo.fingerprint)
            set(sessions.deviceName, deviceInfo.name)
            set(sessions.ipAddress, deviceInfo.ipAddress)
            set(sessions.userAgent, deviceInfo.userAgent)
            set(sessions.location, location)
            set(sessions.latitude, latitude?.toBigDecimal())
            set(sessions.longitude, longitude?.toBigDecimal())
            set(sessions.createdAt, nowLocal)
            set(sessions.lastActivityAt, nowLocal)
            set(sessions.expiresAt, expiresAt.toLocalDateTime(TimeZone.UTC))
            set(sessions.status, SessionStatus.ACTIVE)
            set(sessions.revokedAt, null)
            set(sessions.revokedReason, null)
        }

        return findById(sessionId)!!
    }

    public fun ConnectionScope.findById(sessionId: UUID): Session? {
        return select(sessions)
            .where { (sessions.id eq sessionId) and (sessions.realmId eq realmId) }
            .singleOrNull { it.toSession() }
    }

    public fun ConnectionScope.findByTokenFamily(tokenFamily: UUID): Session? {
        return select(sessions)
            .where { (sessions.tokenFamily eq tokenFamily) and (sessions.realmId eq realmId) }
            .singleOrNull { it.toSession() }
    }

    public fun ConnectionScope.findActiveByUserId(userId: UUID): List<Session> {
        return select(sessions)
            .where {
                (sessions.userId eq userId) and
                    (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.ACTIVE)
            }
            .orderBy(sessions.createdAt, SortOrder.DESC)
            .map { it.toSession() }
    }

    public fun ConnectionScope.countActiveByUserId(userId: UUID): Long {
        return select(sessions)
            .where {
                (sessions.userId eq userId) and
                    (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.ACTIVE)
            }
            .count()
    }

    /**
     * Count active sessions with row-level locking to prevent race conditions.
     * Must be called within a transaction.
     */
    public fun ConnectionScope.countActiveByUserIdForUpdate(userId: UUID): Long {
        return select(sessions)
            .where {
                (sessions.userId eq userId) and
                    (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.ACTIVE)
            }
            .forUpdate()
            .map { Unit }
            .size
            .toLong()
    }

    public fun ConnectionScope.findOldestActiveSessionId(userId: UUID, excludeSessionId: UUID? = null): UUID? {
        return select(sessions)
            .columns(sessions.id)
            .where {
                val baseCondition = (sessions.userId eq userId) and
                    (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.ACTIVE)
                if (excludeSessionId != null) {
                    baseCondition and (sessions.id neq excludeSessionId)
                } else {
                    baseCondition
                }
            }
            .orderBy(sessions.createdAt, SortOrder.ASC)
            .limit(1)
            .map { it[sessions.id] }
            .singleOrNull()
    }

    public fun ConnectionScope.updateActivity(tokenFamily: UUID, now: Instant, newExpiresAt: Instant): Int {
        return update(sessions) {
            set(sessions.lastActivityAt, now.toLocalDateTime(TimeZone.UTC))
            set(sessions.expiresAt, newExpiresAt.toLocalDateTime(TimeZone.UTC))
            where {
                (sessions.tokenFamily eq tokenFamily) and
                    (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.ACTIVE)
            }
        }
    }

    public fun ConnectionScope.revoke(sessionId: UUID, reason: String, now: Instant): Int {
        return update(sessions) {
            set(sessions.status, SessionStatus.REVOKED)
            set(sessions.revokedAt, now.toLocalDateTime(TimeZone.UTC))
            set(sessions.revokedReason, reason)
            where {
                (sessions.id eq sessionId) and (sessions.realmId eq realmId)
            }
        }
    }

    public fun ConnectionScope.revokeAllForUser(userId: UUID, exceptSessionId: UUID?, reason: String, now: Instant): Int {
        val condition = if (exceptSessionId != null) {
            (sessions.userId eq userId) and
                (sessions.realmId eq realmId) and
                (sessions.status eq SessionStatus.ACTIVE) and
                (sessions.id neq exceptSessionId)
        } else {
            (sessions.userId eq userId) and
                (sessions.realmId eq realmId) and
                (sessions.status eq SessionStatus.ACTIVE)
        }

        return update(sessions) {
            set(sessions.status, SessionStatus.REVOKED)
            set(sessions.revokedAt, now.toLocalDateTime(TimeZone.UTC))
            set(sessions.revokedReason, reason)
            where { condition }
        }
    }

    public fun ConnectionScope.markExpired(cutoffTime: Instant): Int {
        return update(sessions) {
            set(sessions.status, SessionStatus.EXPIRED)
            where {
                (sessions.expiresAt less cutoffTime.toLocalDateTime(TimeZone.UTC)) and
                    (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.ACTIVE)
            }
        }
    }

    public fun ConnectionScope.findExpiredSessions(): List<Session> {
        return select(sessions)
            .where {
                (sessions.realmId eq realmId) and
                    (sessions.status eq SessionStatus.EXPIRED)
            }
            .map { it.toSession() }
    }

    public fun ConnectionScope.deleteSession(sessionId: UUID): Int {
        return deleteFrom(sessions)
            .where { (sessions.id eq sessionId) and (sessions.realmId eq realmId) }
            .execute()
    }

    /**
     * Delete multiple sessions in a single batch operation.
     * Returns the number of deleted sessions.
     */
    public fun ConnectionScope.deleteSessions(sessionIds: List<UUID>): Int {
        if (sessionIds.isEmpty()) return 0

        return deleteFrom(sessions)
            .where { (sessions.id inList sessionIds) and (sessions.realmId eq realmId) }
            .execute()
    }

    public fun ConnectionScope.archiveToHistory(session: Session, endReason: String, logoutAt: Instant?): UUID {
        val historyId = UUID.randomUUID()

        insertInto(history) {
            set(history.id, historyId)
            set(history.realmId, this@SessionRepository.realmId)
            set(history.userId, session.userId)
            set(history.sessionId, session.id)
            set(history.deviceName, session.deviceName)
            set(history.ipAddress, session.ipAddress)
            set(history.location, session.location)
            set(history.loginAt, session.createdAt.toLocalDateTime(TimeZone.UTC))
            set(history.logoutAt, logoutAt?.toLocalDateTime(TimeZone.UTC))
            set(history.endReason, endReason)
        }

        return historyId
    }

    /**
     * Archive multiple sessions to history in a single batch operation.
     * Returns a list of history entry IDs in the same order as the input sessions.
     */
    public fun ConnectionScope.archiveSessionsToHistory(
        sessions: List<Session>,
        endReason: String,
        logoutAt: Instant?
    ): List<UUID> {
        if (sessions.isEmpty()) return emptyList()

        val historyIds = sessions.map { UUID.randomUUID() }
        val items = sessions.zip(historyIds)

        batchInsert(history, items) { (session, historyId) ->
            set(history.id, historyId)
            set(history.realmId, this@SessionRepository.realmId)
            set(history.userId, session.userId)
            set(history.sessionId, session.id)
            set(history.deviceName, session.deviceName)
            set(history.ipAddress, session.ipAddress)
            set(history.location, session.location)
            set(history.loginAt, session.createdAt.toLocalDateTime(TimeZone.UTC))
            set(history.logoutAt, logoutAt?.toLocalDateTime(TimeZone.UTC))
            set(history.endReason, endReason)
        }

        return historyIds
    }

    public fun ConnectionScope.findHistoryByUserId(userId: UUID, limit: Int, offset: Int = 0): List<SessionHistoryEntry> {
        return select(history)
            .where {
                (history.userId eq userId) and (history.realmId eq realmId)
            }
            .orderBy(history.loginAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toSessionHistoryEntry() }
    }

    public fun ConnectionScope.countHistoryByUserId(userId: UUID): Long {
        return select(history)
            .where {
                (history.userId eq userId) and (history.realmId eq realmId)
            }
            .count()
    }

    public fun ConnectionScope.deleteOldHistory(cutoffTime: Instant): Int {
        return deleteFrom(history)
            .where {
                (history.loginAt less cutoffTime.toLocalDateTime(TimeZone.UTC)) and
                    (history.realmId eq realmId)
            }
            .execute()
    }

    public fun ConnectionScope.findPreviousDevices(userId: UUID, excludeSessionId: UUID? = null): List<String> {
        return select(sessions)
            .columns(sessions.deviceFingerprint)
            .where {
                val baseCondition = (sessions.userId eq userId) and (sessions.realmId eq realmId)
                if (excludeSessionId != null) {
                    baseCondition and (sessions.id neq excludeSessionId)
                } else {
                    baseCondition
                }
            }
            .map { it[sessions.deviceFingerprint] }
            .distinct()
    }

    public fun ConnectionScope.findPreviousLocations(
        userId: UUID,
        limit: Int = 10,
        excludeSessionId: UUID? = null
    ): List<Pair<Double, Double>> {
        return select(sessions)
            .columns(sessions.latitude, sessions.longitude)
            .where {
                val baseCondition = (sessions.userId eq userId) and
                    (sessions.realmId eq realmId) and
                    sessions.latitude.isNotNull() and
                    sessions.longitude.isNotNull()
                if (excludeSessionId != null) {
                    baseCondition and (sessions.id neq excludeSessionId)
                } else {
                    baseCondition
                }
            }
            .limit(limit)
            .map { row ->
                val lat = row[sessions.latitude]?.toDouble()
                val lon = row[sessions.longitude]?.toDouble()
                if (lat != null && lon != null) lat to lon else null
            }
            .filterNotNull()
    }

    private fun Row.toSession() = Session(
        id = this[sessions.id],
        realmId = this[sessions.realmId],
        userId = this[sessions.userId],
        tokenFamily = this[sessions.tokenFamily],
        deviceFingerprint = this[sessions.deviceFingerprint],
        deviceName = this[sessions.deviceName],
        ipAddress = this[sessions.ipAddress],
        userAgent = this[sessions.userAgent],
        location = this[sessions.location],
        latitude = this[sessions.latitude]?.toDouble(),
        longitude = this[sessions.longitude]?.toDouble(),
        createdAt = this[sessions.createdAt].toInstant(TimeZone.UTC),
        lastActivityAt = this[sessions.lastActivityAt].toInstant(TimeZone.UTC),
        expiresAt = this[sessions.expiresAt].toInstant(TimeZone.UTC),
        status = this[sessions.status],
        revokedAt = this[sessions.revokedAt]?.toInstant(TimeZone.UTC),
        revokedReason = this[sessions.revokedReason]
    )

    private fun Row.toSessionHistoryEntry() = SessionHistoryEntry(
        id = this[history.id],
        realmId = this[history.realmId],
        userId = this[history.userId],
        sessionId = this[history.sessionId],
        deviceName = this[history.deviceName],
        ipAddress = this[history.ipAddress],
        location = this[history.location],
        loginAt = this[history.loginAt].toInstant(TimeZone.UTC),
        logoutAt = this[history.logoutAt]?.toInstant(TimeZone.UTC),
        endReason = this[history.endReason]
    )
}
