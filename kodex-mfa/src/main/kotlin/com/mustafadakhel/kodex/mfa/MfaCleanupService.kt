package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.mfa.database.MfaChallenges
import com.mustafadakhel.kodex.mfa.database.MfaTrustedDevices
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

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
     * Runs all cleanup operations.
     * This is the recommended method for periodic cleanup tasks.
     * @return Triple of (challenges deleted, sessions deleted, trusted devices deleted)
     */
    public suspend fun cleanupAll(): Triple<Int, Int, Int>
}

internal class DefaultMfaCleanupService(
    private val realmId: String,
    private val timeZone: TimeZone,
    private val sessionStore: MfaSessionStore
) : MfaCleanupService {

    override suspend fun cleanupExpiredChallenges(): Int {
        val now = Clock.System.now().toLocalDateTime(timeZone)

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
        val now = Clock.System.now().toLocalDateTime(timeZone)

        return kodexTransaction {
            MfaTrustedDevices.deleteWhere {
                (MfaTrustedDevices.realmId eq realmId) and
                (MfaTrustedDevices.expiresAt.isNotNull()) and
                (MfaTrustedDevices.expiresAt less now)
            }
        }
    }

    override suspend fun cleanupAll(): Triple<Int, Int, Int> {
        val challengesDeleted = cleanupExpiredChallenges()
        val sessionsDeleted = cleanupExpiredSessions()
        val trustedDevicesDeleted = cleanupExpiredTrustedDevices()
        return Triple(challengesDeleted, sessionsDeleted, trustedDevicesDeleted)
    }
}
