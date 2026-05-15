package com.mustafadakhel.kodex.sessions.database

import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import com.mustafadakhel.kodex.sessions.schema.SessionSchema
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import java.util.UUID

public class SessionRepository(
    private val db: KodexDatabase,
    private val schema: SessionSchema,
    public val realmId: String
) {
    private val sessions = schema.sessions
    private val history = schema.sessionHistory

    public fun create(
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

        sessions.insert {
            it[sessions.id] = sessionId
            it[sessions.realmId] = this@SessionRepository.realmId
            it[sessions.userId] = userId
            it[sessions.tokenFamily] = tokenFamily
            it[sessions.deviceFingerprint] = deviceInfo.fingerprint
            it[sessions.deviceName] = deviceInfo.name
            it[sessions.ipAddress] = deviceInfo.ipAddress
            it[sessions.userAgent] = deviceInfo.userAgent
            it[sessions.location] = location
            it[sessions.latitude] = latitude?.toBigDecimal()
            it[sessions.longitude] = longitude?.toBigDecimal()
            it[sessions.createdAt] = now.toLocalDateTime(TimeZone.UTC)
            it[sessions.lastActivityAt] = now.toLocalDateTime(TimeZone.UTC)
            it[sessions.expiresAt] = expiresAt.toLocalDateTime(TimeZone.UTC)
            it[sessions.status] = SessionStatus.ACTIVE
            it[sessions.revokedAt] = null
            it[sessions.revokedReason] = null
        }

        return findById(sessionId)!!
    }

    public fun findById(sessionId: UUID): Session? {
        return sessions
            .selectAll()
            .where { (sessions.id eq sessionId) and (sessions.realmId eq realmId) }
            .map { it.toSession() }
            .singleOrNull()
    }

    public fun findByTokenFamily(tokenFamily: UUID): Session? {
        return sessions
            .selectAll()
            .where { (sessions.tokenFamily eq tokenFamily) and (sessions.realmId eq realmId) }
            .map { it.toSession() }
            .singleOrNull()
    }

    public fun findActiveByUserId(userId: UUID): List<Session> {
        return sessions
            .selectAll()
            .where {
                (sessions.userId eq userId) and
                (sessions.realmId eq realmId) and
                (sessions.status eq SessionStatus.ACTIVE)
            }
            .orderBy(sessions.createdAt, SortOrder.DESC)
            .map { it.toSession() }
    }

    public fun countActiveByUserId(userId: UUID): Long {
        return sessions
            .selectAll()
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
    public fun countActiveByUserIdForUpdate(userId: UUID): Long {
        return sessions
            .selectAll()
            .where {
                (sessions.userId eq userId) and
                (sessions.realmId eq realmId) and
                (sessions.status eq SessionStatus.ACTIVE)
            }
            .forUpdate()
            .toList()
            .size
            .toLong()
    }

    public fun findOldestActiveSessionId(userId: UUID, excludeSessionId: UUID? = null): UUID? {
        return sessions
            .select(sessions.id)
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

    public fun updateActivity(tokenFamily: UUID, now: Instant, newExpiresAt: Instant): Int {
        return sessions.update({
            (sessions.tokenFamily eq tokenFamily) and
            (sessions.realmId eq realmId) and
            (sessions.status eq SessionStatus.ACTIVE)
        }) {
            it[sessions.lastActivityAt] = now.toLocalDateTime(TimeZone.UTC)
            it[sessions.expiresAt] = newExpiresAt.toLocalDateTime(TimeZone.UTC)
        }
    }

    public fun revoke(sessionId: UUID, reason: String, now: Instant): Int {
        return sessions.update({
            (sessions.id eq sessionId) and
            (sessions.realmId eq realmId)
        }) {
            it[sessions.status] = SessionStatus.REVOKED
            it[sessions.revokedAt] = now.toLocalDateTime(TimeZone.UTC)
            it[sessions.revokedReason] = reason
        }
    }

    public fun revokeAllForUser(userId: UUID, exceptSessionId: UUID?, reason: String, now: Instant): Int {
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

        return sessions.update({ condition }) {
            it[sessions.status] = SessionStatus.REVOKED
            it[sessions.revokedAt] = now.toLocalDateTime(TimeZone.UTC)
            it[sessions.revokedReason] = reason
        }
    }

    public fun markExpired(cutoffTime: Instant): Int {
        return sessions.update({
            (sessions.expiresAt less cutoffTime.toLocalDateTime(TimeZone.UTC)) and
            (sessions.realmId eq realmId) and
            (sessions.status eq SessionStatus.ACTIVE)
        }) {
            it[sessions.status] = SessionStatus.EXPIRED
        }
    }

    public fun findExpiredSessions(): List<Session> {
        return sessions
            .selectAll()
            .where {
                (sessions.realmId eq realmId) and
                (sessions.status eq SessionStatus.EXPIRED)
            }
            .map { it.toSession() }
    }

    public fun deleteSession(sessionId: UUID): Int {
        return sessions.deleteWhere {
            (sessions.id eq sessionId) and
            (sessions.realmId eq realmId)
        }
    }

    /**
     * Delete multiple sessions in a single batch operation.
     * Returns the number of deleted sessions.
     */
    public fun deleteSessions(sessionIds: List<UUID>): Int {
        if (sessionIds.isEmpty()) return 0

        return sessions.deleteWhere {
            (sessions.id inList sessionIds) and
            (sessions.realmId eq realmId)
        }
    }

    public fun archiveToHistory(session: Session, endReason: String, logoutAt: Instant?): UUID {
        val historyId = UUID.randomUUID()

        history.insert {
            it[history.id] = historyId
            it[history.realmId] = this@SessionRepository.realmId
            it[history.userId] = session.userId
            it[history.sessionId] = session.id
            it[history.deviceName] = session.deviceName
            it[history.ipAddress] = session.ipAddress
            it[history.location] = session.location
            it[history.loginAt] = session.createdAt.toLocalDateTime(TimeZone.UTC)
            it[history.logoutAt] = logoutAt?.toLocalDateTime(TimeZone.UTC)
            it[history.endReason] = endReason
        }

        return historyId
    }

    /**
     * Archive multiple sessions to history in a single batch operation.
     * Returns a list of history entry IDs in the same order as the input sessions.
     */
    public fun archiveSessionsToHistory(sessions: List<Session>, endReason: String, logoutAt: Instant?): List<UUID> {
        if (sessions.isEmpty()) return emptyList()

        val historyIds = sessions.map { UUID.randomUUID() }

        history.batchInsert(sessions.zip(historyIds)) { (session, historyId) ->
            this[history.id] = historyId
            this[history.realmId] = this@SessionRepository.realmId
            this[history.userId] = session.userId
            this[history.sessionId] = session.id
            this[history.deviceName] = session.deviceName
            this[history.ipAddress] = session.ipAddress
            this[history.location] = session.location
            this[history.loginAt] = session.createdAt.toLocalDateTime(TimeZone.UTC)
            this[history.logoutAt] = logoutAt?.toLocalDateTime(TimeZone.UTC)
            this[history.endReason] = endReason
        }

        return historyIds
    }

    public fun findHistoryByUserId(userId: UUID, limit: Int, offset: Int = 0): List<SessionHistoryEntry> {
        return history
            .selectAll()
            .where {
                (history.userId eq userId) and
                (history.realmId eq realmId)
            }
            .orderBy(history.loginAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toSessionHistoryEntry() }
    }

    public fun countHistoryByUserId(userId: UUID): Long {
        return history
            .selectAll()
            .where {
                (history.userId eq userId) and
                (history.realmId eq realmId)
            }
            .count()
    }

    public fun deleteOldHistory(cutoffTime: Instant): Int {
        return history.deleteWhere {
            (history.loginAt less cutoffTime.toLocalDateTime(TimeZone.UTC)) and
            (history.realmId eq realmId)
        }
    }

    public fun findPreviousDevices(userId: UUID, excludeSessionId: UUID? = null): List<String> {
        return sessions
            .select(sessions.deviceFingerprint)
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

    public fun findPreviousLocations(userId: UUID, limit: Int = 10, excludeSessionId: UUID? = null): List<Pair<Double, Double>> {
        return sessions
            .select(sessions.latitude, sessions.longitude)
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
            .mapNotNull {
                val lat = it[sessions.latitude]?.toDouble()
                val lon = it[sessions.longitude]?.toDouble()
                if (lat != null && lon != null) lat to lon else null
            }
    }

    private fun ResultRow.toSession() = Session(
        id = this[sessions.id],
        realmId = this[sessions.realmId],
        userId = this[sessions.userId].value,
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

    private fun ResultRow.toSessionHistoryEntry() = SessionHistoryEntry(
        id = this[history.id],
        realmId = this[history.realmId],
        userId = this[history.userId].value,
        sessionId = this[history.sessionId],
        deviceName = this[history.deviceName],
        ipAddress = this[history.ipAddress],
        location = this[history.location],
        loginAt = this[history.loginAt].toInstant(TimeZone.UTC),
        logoutAt = this[history.logoutAt]?.toInstant(TimeZone.UTC),
        endReason = this[history.endReason]
    )
}
