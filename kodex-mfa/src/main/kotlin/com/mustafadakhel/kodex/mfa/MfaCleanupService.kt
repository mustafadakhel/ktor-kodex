@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.isNotNull
import com.mustafadakhel.kodex.jdbc.less
import com.mustafadakhel.kodex.mfa.schema.MfaSchema
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

public interface MfaCleanupService {
    /**
     * Removes expired MFA challenges from the database.
     * Cleans up all expired challenges across all users in this realm.
     * @return Number of challenges deleted
     */
    public suspend fun cleanupExpiredChallenges(): Int

    /**
     * Removes expired MFA sessions from the in-memory store.
     * Cleans up all expired sessions across all users in this realm.
     * @return Number of sessions deleted
     */
    public suspend fun cleanupExpiredSessions(): Int

    /**
     * Removes expired trusted devices from the database.
     * Only removes devices with a non-null expiresAt timestamp that has passed.
     * @return Number of trusted devices deleted
     */
    public suspend fun cleanupExpiredTrustedDevices(): Int

    /**
     * Removes abandoned TOTP enrollments from the database.
     * Cleans up inactive TOTP methods that have exceeded the enrollment expiration period.
     * @return Number of abandoned enrollments deleted
     */
    public suspend fun cleanupAbandonedEnrollments(): Int

    /**
     * Runs all cleanup operations.
     * This is the recommended method for periodic cleanup tasks.
     * @return Quadruple of (challenges deleted, sessions deleted, trusted devices deleted, abandoned enrollments deleted)
     */
    public suspend fun cleanupAll(): List<Int>
}

internal class DefaultMfaCleanupService(
    private val db: KodexDatabase,
    private val schema: MfaSchema,
    private val realmId: String,
    private val timeZone: TimeZone,
    private val sessionStore: MfaSessionStore,
    private val inactiveEnrollmentExpiration: Duration
) : MfaCleanupService {

    private val challenges = schema.mfaChallenges
    private val trustedDevices = schema.mfaTrustedDevices
    private val methods = schema.mfaMethods

    override suspend fun cleanupExpiredChallenges(): Int {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

        return db.transaction {
            deleteFrom(challenges)
                .where { (challenges.realmId eq realmId) and (challenges.expiresAt less now) }
                .execute()
        }
    }

    override suspend fun cleanupExpiredSessions(): Int {
        return sessionStore.cleanupExpiredSessions()
    }

    override suspend fun cleanupExpiredTrustedDevices(): Int {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

        return db.transaction {
            deleteFrom(trustedDevices)
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.expiresAt.isNotNull()) and
                    (trustedDevices.expiresAt less now)
                }
                .execute()
        }
    }

    override suspend fun cleanupAbandonedEnrollments(): Int {
        val cutoffTime = CurrentKotlinInstant.minus(inactiveEnrollmentExpiration).toLocalDateTime(timeZone)

        return db.transaction {
            deleteFrom(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.methodType eq MfaMethodType.TOTP) and
                    (methods.isActive eq false) and
                    (methods.enrolledAt less cutoffTime)
                }
                .execute()
        }
    }

    override suspend fun cleanupAll(): List<Int> {
        val challengesDeleted = cleanupExpiredChallenges()
        val sessionsDeleted = cleanupExpiredSessions()
        val trustedDevicesDeleted = cleanupExpiredTrustedDevices()
        val abandonedEnrollmentsDeleted = cleanupAbandonedEnrollments()
        return listOf(challengesDeleted, sessionsDeleted, trustedDevicesDeleted, abandonedEnrollmentsDeleted)
    }
}
