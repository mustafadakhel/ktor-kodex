package com.mustafadakhel.kodex.token.cleanup

import com.mustafadakhel.kodex.observability.KodexLogger
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Periodically removes expired and revoked tokens from the database.
 *
 * @param realmId the realm whose tokens should be cleaned up
 * @param cleanupInterval how often the cleanup runs (default: 1 hour)
 * @param retentionPeriod how long after expiration a token is kept before deletion (default: 7 days)
 * @param timeZone used to convert instants to local date-times for database queries
 */
public class TokenCleanupService(
    private val db: KodexDatabase,
    private val realmId: String,
    private val cleanupInterval: Duration = 1.hours,
    private val retentionPeriod: Duration = 7.days,
    private val timeZone: TimeZone = TimeZone.UTC
) {
    private val repository = TokenCleanupRepository(db, realmId)
    private val logger = KodexLogger.logger<TokenCleanupService>()
    @Volatile private var cleanupJob: Job? = null

    public fun start(scope: CoroutineScope) {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(cleanupInterval)
            while (isActive) {
                try {
                    cleanup()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Token cleanup failed: ${e.message}", e)
                }
                delay(cleanupInterval)
            }
        }
    }

    public fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    public fun cleanup() {
        val cutoff = (CurrentKotlinInstant - retentionPeriod).toLocalDateTime(timeZone)

        val expiredCount = repository.deleteExpiredTokens(cutoff)
        val revokedCount = repository.deleteRevokedTokens(cutoff)

        logger.info(
            "Token cleanup completed: removed {} expired tokens, {} revoked tokens",
            expiredCount,
            revokedCount
        )
    }
}
