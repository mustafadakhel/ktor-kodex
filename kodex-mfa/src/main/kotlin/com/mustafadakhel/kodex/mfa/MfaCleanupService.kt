package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.mfa.database.MfaChallenges
import com.mustafadakhel.kodex.mfa.database.MfaMethodType
import com.mustafadakhel.kodex.mfa.database.MfaMethods
import com.mustafadakhel.kodex.mfa.database.MfaTrustedDevices
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
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
    private val realmId: String,
    private val timeZone: TimeZone,
    private val sessionStore: MfaSessionStore,
    private val inactiveEnrollmentExpiration: Duration
) : MfaCleanupService {

    override suspend fun cleanupExpiredChallenges(): Int {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

        return kodexTransaction {
            MfaChallenges.deleteWhere {
                (MfaChallenges.realmId eq realmId) and (MfaChallenges.expiresAt less now)
            }
        }
    }

    override suspend fun cleanupExpiredSessions(): Int {
        return sessionStore.cleanupExpiredSessions()
    }

    override suspend fun cleanupExpiredTrustedDevices(): Int {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

        return kodexTransaction {
            MfaTrustedDevices.deleteWhere {
                (MfaTrustedDevices.realmId eq realmId) and
                (MfaTrustedDevices.expiresAt.isNotNull()) and
                (MfaTrustedDevices.expiresAt less now)
            }
        }
    }

    override suspend fun cleanupAbandonedEnrollments(): Int {
        val cutoffTime = CurrentKotlinInstant.minus(inactiveEnrollmentExpiration).toLocalDateTime(timeZone)

        return kodexTransaction {
            MfaMethods.deleteWhere {
                (MfaMethods.realmId eq realmId) and
                (MfaMethods.methodType eq MfaMethodType.TOTP) and
                (MfaMethods.isActive eq false) and
                (MfaMethods.enrolledAt less cutoffTime)
            }
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
