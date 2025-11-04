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

            if (deletedInBatch == batchSize) {
                delay(10)
            }
        } while (deletedInBatch == batchSize)

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
