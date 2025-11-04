package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.verification.database.VerificationTokens
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Service for cleaning up expired and used verification tokens.
 */
public interface TokenCleanupService {
    /**
     * Purges expired and used tokens older than the retention period.
     *
     * Deletes tokens that are either:
     * - Expired and older than retentionPeriod
     * - Used and older than retentionPeriod
     *
     * @param retentionPeriod How long to keep tokens after expiry/use. Default: 30 days
     * @return Number of tokens deleted
     */
    public suspend fun purgeExpiredTokens(retentionPeriod: Duration = 30.days): Int
}

/**
 * Default implementation of token cleanup service.
 */
internal class DefaultTokenCleanupService(
    private val timeZone: TimeZone,
    private val eventBus: com.mustafadakhel.kodex.event.EventBus?,
    private val realm: String
) : TokenCleanupService {

    override suspend fun purgeExpiredTokens(retentionPeriod: Duration): Int {
        val now = Clock.System.now().toLocalDateTime(timeZone)
        val clockNow = Clock.System.now()
        val cutoff = clockNow.minus(retentionPeriod).toLocalDateTime(timeZone)

        // PERFORMANCE: Delete in batches to avoid long table locks
        // Delete max 1000 tokens per transaction to prevent blocking other operations
        val batchSize = 1000
        var totalDeleted = 0

        do {
            val deletedInBatch = kodexTransaction {
                VerificationTokens.deleteWhere(limit = batchSize) {
                    (usedAt.isNotNull() and (usedAt less cutoff)) or
                    ((expiresAt less now) and (createdAt less cutoff))
                }
            }

            totalDeleted += deletedInBatch

            // Small delay between batches to allow other operations to proceed
            if (deletedInBatch == batchSize) {
                delay(10) // 10ms pause between batches
            }
        } while (deletedInBatch == batchSize) // Continue if we deleted a full batch

        // Emit cleanup event
        if (totalDeleted > 0) {
            eventBus?.publish(com.mustafadakhel.kodex.event.TokenCleanupEvent.TokensCleanedUp(
                eventId = java.util.UUID.randomUUID(),
                timestamp = clockNow,
                realmId = realm,
                tokenType = "verification",
                tokensRemoved = totalDeleted
            ))
        }

        return totalDeleted
    }
}
