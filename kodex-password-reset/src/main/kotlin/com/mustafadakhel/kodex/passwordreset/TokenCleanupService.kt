@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.TokenCleanupEvent
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.isNotNull
import com.mustafadakhel.kodex.jdbc.less
import com.mustafadakhel.kodex.jdbc.or
import com.mustafadakhel.kodex.passwordreset.schema.PasswordResetSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

public interface TokenCleanupService {
    public suspend fun purgeExpiredTokens(retentionPeriod: Duration = 30.days): Int
}

internal class DefaultTokenCleanupService(
    private val db: KodexDatabase,
    private val schema: PasswordResetSchema,
    private val timeZone: TimeZone,
    private val eventBus: EventBus?,
    private val realm: String
) : TokenCleanupService {

    private val tokens = schema.passwordResetTokens

    override suspend fun purgeExpiredTokens(retentionPeriod: Duration): Int {
        val clockNow = CurrentKotlinInstant
        val now = clockNow.toLocalDateTime(timeZone)
        val cutoff = clockNow.minus(retentionPeriod).toLocalDateTime(timeZone)

        val batchSize = 1000
        var totalDeleted = 0

        do {
            val deletedInBatch = db.transaction {
                deleteFrom(tokens)
                    .where {
                        (tokens.realmId eq realm) and (
                            (tokens.usedAt.isNotNull() and (tokens.usedAt less cutoff)) or
                            ((tokens.expiresAt less now) and (tokens.createdAt less cutoff))
                        )
                    }
                    .limit(batchSize)
                    .execute()
            }

            totalDeleted += deletedInBatch

            if (deletedInBatch == batchSize) {
                delay(10)
            }
        } while (deletedInBatch == batchSize)

        if (totalDeleted > 0) {
            eventBus?.publish(TokenCleanupEvent.TokensCleanedUp(
                eventId = UUID.randomUUID(),
                timestamp = clockNow,
                realmId = realm,
                tokenType = "password_reset",
                tokensRemoved = totalDeleted
            ))
        }

        return totalDeleted
    }
}
