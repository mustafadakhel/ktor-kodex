package com.mustafadakhel.kodex.sessions.database

import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.model.Session
import com.mustafadakhel.kodex.sessions.model.SessionHistoryEntry
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import java.util.UUID
import kotlin.time.Duration

public class SessionRepository(
    public val realmId: String
) {
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

        Sessions.insert {
            it[id] = sessionId
            it[Sessions.realmId] = this@SessionRepository.realmId
            it[Sessions.userId] = userId
            it[Sessions.tokenFamily] = tokenFamily
            it[deviceFingerprint] = deviceInfo.fingerprint
            it[deviceName] = deviceInfo.name
            it[ipAddress] = deviceInfo.ipAddress
            it[userAgent] = deviceInfo.userAgent
            it[Sessions.location] = location
            it[Sessions.latitude] = latitude?.toBigDecimal()
            it[Sessions.longitude] = longitude?.toBigDecimal()
            it[createdAt] = now.toLocalDateTime(TimeZone.UTC)
            it[lastActivityAt] = now.toLocalDateTime(TimeZone.UTC)
            it[Sessions.expiresAt] = expiresAt.toLocalDateTime(TimeZone.UTC)
            it[Sessions.status] = SessionStatus.ACTIVE
            it[Sessions.revokedAt] = null
            it[Sessions.revokedReason] = null
        }

        return findById(sessionId)!!
    }

    public fun findById(sessionId: UUID): Session? {
        return Sessions
            .selectAll()
            .where { (Sessions.id eq sessionId) and (Sessions.realmId eq realmId) }
            .map { it.toSession() }
            .singleOrNull()
    }

    public fun findByTokenFamily(tokenFamily: UUID): Session? {
        return Sessions
            .selectAll()
            .where { (Sessions.tokenFamily eq tokenFamily) and (Sessions.realmId eq realmId) }
            .map { it.toSession() }
            .singleOrNull()
    }

    public fun findActiveByUserId(userId: UUID): List<Session> {
        return Sessions
            .selectAll()
            .where {
                (Sessions.userId eq userId) and
                (Sessions.realmId eq realmId) and
                (Sessions.status eq SessionStatus.ACTIVE)
            }
            .orderBy(Sessions.createdAt, SortOrder.DESC)
            .map { it.toSession() }
    }

    public fun countActiveByUserId(userId: UUID): Long {
        return Sessions
            .selectAll()
            .where {
                (Sessions.userId eq userId) and
                (Sessions.realmId eq realmId) and
                (Sessions.status eq SessionStatus.ACTIVE)
            }
            .count()
    }

    /**
     * Count active sessions with row-level locking to prevent race conditions.
     * Must be called within a transaction.
     */
    public fun countActiveByUserIdForUpdate(userId: UUID): Long {
        return Sessions
            .selectAll()
            .where {
                (Sessions.userId eq userId) and
                (Sessions.realmId eq realmId) and
                (Sessions.status eq SessionStatus.ACTIVE)
            }
            .count()
    }

    public fun findOldestActiveSessionId(userId: UUID, excludeSessionId: UUID? = null): UUID? {
        return Sessions
            .select(Sessions.id)
            .where {
                val baseCondition = (Sessions.userId eq userId) and
                    (Sessions.realmId eq realmId) and
                    (Sessions.status eq SessionStatus.ACTIVE)
                if (excludeSessionId != null) {
                    baseCondition and (Sessions.id neq excludeSessionId)
                } else {
                    baseCondition
                }
            }
            .orderBy(Sessions.createdAt, SortOrder.ASC)
            .limit(1)
            .map { it[Sessions.id] }
            .singleOrNull()
    }

    public fun updateActivity(tokenFamily: UUID, now: Instant, newExpiresAt: Instant): Int {
        return Sessions.update({
            (Sessions.tokenFamily eq tokenFamily) and
            (Sessions.realmId eq realmId) and
            (Sessions.status eq SessionStatus.ACTIVE)
        }) {
            it[lastActivityAt] = now.toLocalDateTime(TimeZone.UTC)
            it[expiresAt] = newExpiresAt.toLocalDateTime(TimeZone.UTC)
        }
    }

    public fun revoke(sessionId: UUID, reason: String, now: Instant): Int {
        return Sessions.update({
            (Sessions.id eq sessionId) and
            (Sessions.realmId eq realmId)
        }) {
            it[Sessions.status] = SessionStatus.REVOKED
            it[Sessions.revokedAt] = now.toLocalDateTime(TimeZone.UTC)
            it[Sessions.revokedReason] = reason
        }
    }

    public fun revokeAllForUser(userId: UUID, exceptSessionId: UUID?, reason: String, now: Instant): Int {
        val condition = if (exceptSessionId != null) {
            (Sessions.userId eq userId) and
            (Sessions.realmId eq realmId) and
            (Sessions.status eq SessionStatus.ACTIVE) and
            (Sessions.id neq exceptSessionId)
        } else {
            (Sessions.userId eq userId) and
            (Sessions.realmId eq realmId) and
            (Sessions.status eq SessionStatus.ACTIVE)
        }

        return Sessions.update({ condition }) {
            it[Sessions.status] = SessionStatus.REVOKED
            it[Sessions.revokedAt] = now.toLocalDateTime(TimeZone.UTC)
            it[Sessions.revokedReason] = reason
        }
    }

    public fun markExpired(cutoffTime: Instant): Int {
        return Sessions.update({
            (Sessions.expiresAt less cutoffTime.toLocalDateTime(TimeZone.UTC)) and
            (Sessions.realmId eq realmId) and
            (Sessions.status eq SessionStatus.ACTIVE)
        }) {
            it[Sessions.status] = SessionStatus.EXPIRED
        }
    }

    public fun findExpiredSessions(): List<Session> {
        return Sessions
            .selectAll()
            .where {
                (Sessions.realmId eq realmId) and
                (Sessions.status eq SessionStatus.EXPIRED)
            }
            .map { it.toSession() }
    }

    public fun deleteSession(sessionId: UUID): Int {
        return Sessions.deleteWhere {
            (Sessions.id eq sessionId) and
            (Sessions.realmId eq realmId)
        }
    }

    /**
     * Delete multiple sessions in a single batch operation.
     * Returns the number of deleted sessions.
     */
    public fun deleteSessions(sessionIds: List<UUID>): Int {
        if (sessionIds.isEmpty()) return 0

        return Sessions.deleteWhere {
            (Sessions.id inList sessionIds) and
            (Sessions.realmId eq realmId)
        }
    }

    public fun archiveToHistory(session: Session, endReason: String, logoutAt: Instant?): UUID {
        val historyId = UUID.randomUUID()

        SessionHistory.insert {
            it[id] = historyId
            it[SessionHistory.realmId] = this@SessionRepository.realmId
            it[SessionHistory.userId] = session.userId
            it[sessionId] = session.id
            it[deviceName] = session.deviceName
            it[ipAddress] = session.ipAddress
            it[location] = session.location
            it[loginAt] = session.createdAt.toLocalDateTime(TimeZone.UTC)
            it[SessionHistory.logoutAt] = logoutAt?.toLocalDateTime(TimeZone.UTC)
            it[SessionHistory.endReason] = endReason
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

        SessionHistory.batchInsert(sessions.zip(historyIds)) { (session, historyId) ->
            this[SessionHistory.id] = historyId
            this[SessionHistory.realmId] = this@SessionRepository.realmId
            this[SessionHistory.userId] = session.userId
            this[SessionHistory.sessionId] = session.id
            this[SessionHistory.deviceName] = session.deviceName
            this[SessionHistory.ipAddress] = session.ipAddress
            this[SessionHistory.location] = session.location
            this[SessionHistory.loginAt] = session.createdAt.toLocalDateTime(TimeZone.UTC)
            this[SessionHistory.logoutAt] = logoutAt?.toLocalDateTime(TimeZone.UTC)
            this[SessionHistory.endReason] = endReason
        }

        return historyIds
    }

    public fun findHistoryByUserId(userId: UUID, limit: Int, offset: Int = 0): List<SessionHistoryEntry> {
        return SessionHistory
            .selectAll()
            .where {
                (SessionHistory.userId eq userId) and
                (SessionHistory.realmId eq realmId)
            }
            .orderBy(SessionHistory.loginAt, SortOrder.DESC)
            .limit(limit)
            .offset(offset.toLong())
            .map { it.toSessionHistoryEntry() }
    }

    public fun countHistoryByUserId(userId: UUID): Long {
        return SessionHistory
            .selectAll()
            .where {
                (SessionHistory.userId eq userId) and
                (SessionHistory.realmId eq realmId)
            }
            .count()
    }

    public fun deleteOldHistory(cutoffTime: Instant): Int {
        return SessionHistory.deleteWhere {
            (SessionHistory.loginAt less cutoffTime.toLocalDateTime(TimeZone.UTC)) and
            (SessionHistory.realmId eq realmId)
        }
    }

    public fun findPreviousDevices(userId: UUID, excludeSessionId: UUID? = null): List<String> {
        return Sessions
            .select(Sessions.deviceFingerprint)
            .where {
                val baseCondition = (Sessions.userId eq userId) and (Sessions.realmId eq realmId)
                if (excludeSessionId != null) {
                    baseCondition and (Sessions.id neq excludeSessionId)
                } else {
                    baseCondition
                }
            }
            .map { it[Sessions.deviceFingerprint] }
            .distinct()
    }

    public fun findPreviousLocations(userId: UUID, limit: Int = 10, excludeSessionId: UUID? = null): List<Pair<Double, Double>> {
        return Sessions
            .select(Sessions.latitude, Sessions.longitude)
            .where {
                val baseCondition = (Sessions.userId eq userId) and
                    (Sessions.realmId eq realmId) and
                    Sessions.latitude.isNotNull() and
                    Sessions.longitude.isNotNull()
                if (excludeSessionId != null) {
                    baseCondition and (Sessions.id neq excludeSessionId)
                } else {
                    baseCondition
                }
            }
            .limit(limit)
            .mapNotNull {
                val lat = it[Sessions.latitude]?.toDouble()
                val lon = it[Sessions.longitude]?.toDouble()
                if (lat != null && lon != null) lat to lon else null
            }
    }

    private fun ResultRow.toSession() = Session(
        id = this[Sessions.id],
        realmId = this[Sessions.realmId],
        userId = this[Sessions.userId],
        tokenFamily = this[Sessions.tokenFamily],
        deviceFingerprint = this[Sessions.deviceFingerprint],
        deviceName = this[Sessions.deviceName],
        ipAddress = this[Sessions.ipAddress],
        userAgent = this[Sessions.userAgent],
        location = this[Sessions.location],
        latitude = this[Sessions.latitude]?.toDouble(),
        longitude = this[Sessions.longitude]?.toDouble(),
        createdAt = this[Sessions.createdAt].toInstant(TimeZone.UTC),
        lastActivityAt = this[Sessions.lastActivityAt].toInstant(TimeZone.UTC),
        expiresAt = this[Sessions.expiresAt].toInstant(TimeZone.UTC),
        status = this[Sessions.status],
        revokedAt = this[Sessions.revokedAt]?.toInstant(TimeZone.UTC),
        revokedReason = this[Sessions.revokedReason]
    )

    private fun ResultRow.toSessionHistoryEntry() = SessionHistoryEntry(
        id = this[SessionHistory.id],
        realmId = this[SessionHistory.realmId],
        userId = this[SessionHistory.userId],
        sessionId = this[SessionHistory.sessionId],
        deviceName = this[SessionHistory.deviceName],
        ipAddress = this[SessionHistory.ipAddress],
        location = this[SessionHistory.location],
        loginAt = this[SessionHistory.loginAt].toInstant(TimeZone.UTC),
        logoutAt = this[SessionHistory.logoutAt]?.toInstant(TimeZone.UTC),
        endReason = this[SessionHistory.endReason]
    )
}
