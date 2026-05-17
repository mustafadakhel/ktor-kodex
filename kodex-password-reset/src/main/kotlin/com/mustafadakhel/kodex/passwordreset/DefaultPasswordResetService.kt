@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.PasswordResetEvent
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.isNull
import com.mustafadakhel.kodex.jdbc.neq
import com.mustafadakhel.kodex.passwordreset.schema.PasswordResetSchema
import com.mustafadakhel.kodex.ratelimit.RateLimitReservation
import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.tokens.token.HexFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.tokens.token.TokenHasher
import com.mustafadakhel.kodex.tokens.token.TokenValidator
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

internal class DefaultPasswordResetService(
    private val db: KodexDatabase,
    private val schema: PasswordResetSchema,
    private val config: PasswordResetConfigData,
    private val passwordResetSender: PasswordResetSender,
    private val timeZone: TimeZone,
    private val eventBus: EventBus?,
    private val realm: String,
    private val rateLimiter: RateLimiter
) : PasswordResetService {

    private val contacts = schema.passwordResetContacts
    private val tokens = schema.passwordResetTokens

    override suspend fun initiatePasswordReset(
        identifier: String,
        contactType: PasswordResetService.ContactType,
        ipAddress: String?
    ): PasswordResetResult {
        val identifierReservation = rateLimiter.checkAndReserve(
            key = "reset:identifier:$identifier",
            limit = config.rateLimit.maxAttemptsPerIdentifier,
            window = config.rateLimit.window,
            cooldown = config.rateLimit.cooldown
        )
        if (!identifierReservation.isAllowed()) {
            val reason = when (val result = identifierReservation.result) {
                is RateLimitResult.Exceeded -> result.reason
                is RateLimitResult.Cooldown -> result.reason
                else -> "Rate limit check failed"
            }
            return PasswordResetResult.RateLimitExceeded(reason)
        }

        var ipReservation: RateLimitReservation? = null
        if (ipAddress != null) {
            ipReservation = rateLimiter.checkAndReserve(
                key = "reset:ip:$ipAddress",
                limit = config.rateLimit.maxAttemptsPerIp,
                window = config.rateLimit.window,
                cooldown = config.rateLimit.cooldown
            )
            if (!ipReservation.isAllowed()) {
                rateLimiter.releaseReservation(identifierReservation.reservationId)
                val reason = when (val result = ipReservation.result) {
                    is RateLimitResult.Exceeded -> result.reason
                    is RateLimitResult.Cooldown -> result.reason
                    else -> "Rate limit check failed"
                }
                return PasswordResetResult.RateLimitExceeded(reason)
            }
        }

        val clockNow = CurrentKotlinInstant
        val now = clockNow.toLocalDateTime(timeZone)

        val token = TokenGenerator.generate(HexFormat())
        val expiresAt = ExpirationCalculator.calculateExpiration(config.tokenValidity, timeZone, clockNow)

        val userId = db.transaction {
            select(contacts)
                .where {
                    (contacts.realmId eq realm) and
                    (contacts.contactType eq contactType.name) and
                    (contacts.contactValue eq identifier)
                }
                .singleOrNull { row -> row[contacts.userId] }
        }

        if (userId == null) {
            return PasswordResetResult.Success
        }

        val userReservation = rateLimiter.checkAndReserve(
            key = "reset:user:$userId",
            limit = config.rateLimit.maxAttemptsPerUser,
            window = config.rateLimit.window,
            cooldown = config.rateLimit.cooldown
        )
        if (!userReservation.isAllowed()) {
            rateLimiter.releaseReservation(identifierReservation.reservationId)
            rateLimiter.releaseReservation(ipReservation?.reservationId)
            return PasswordResetResult.Success
        }

        val userReservationId = userReservation.reservationId

        val hashedToken = TokenHasher.hash(token)

        db.transaction {
            insertInto(tokens) {
                set(tokens.realmId, realm)
                set(tokens.userId, userId)
                set(tokens.token, hashedToken)
                set(tokens.contactValue, identifier)
                set(tokens.createdAt, now)
                set(tokens.expiresAt, expiresAt)
                set(tokens.usedAt, null)
                set(tokens.ipAddress, ipAddress)
            }
        }

        try {
            passwordResetSender.send(
                recipient = identifier,
                token = token,
                expiresAt = expiresAt.toString()
            )
        } catch (e: Exception) {
            db.transaction {
                deleteFrom(tokens)
                    .where {
                        (tokens.realmId eq realm) and
                            (tokens.userId eq userId) and
                            (tokens.token eq hashedToken)
                    }
                    .execute()
            }
            rateLimiter.releaseReservation(identifierReservation.reservationId)
            rateLimiter.releaseReservation(ipReservation?.reservationId)
            rateLimiter.releaseReservation(userReservationId)

            eventBus?.publish(PasswordResetEvent.PasswordResetInitiationFailed(
                eventId = UUID.randomUUID(),
                timestamp = clockNow,
                realmId = realm,
                contactType = contactType.name,
                contactValue = identifier,
                reason = e.message ?: "Send failed"
            ))

            return PasswordResetResult.SendFailed(e.message ?: "Send failed")
        }

        eventBus?.publish(PasswordResetEvent.PasswordResetInitiated(
            eventId = UUID.randomUUID(),
            timestamp = clockNow,
            realmId = realm,
            userId = userId,
            contactType = contactType.name,
            contactValue = identifier
        ))

        return PasswordResetResult.Success
    }

    override suspend fun verifyResetToken(token: String): TokenVerificationResult {
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)

        val result = db.transaction {
            val resetToken = select(tokens)
                .where {
                    (tokens.realmId eq realm) and (tokens.token eq TokenHasher.hash(token))
                }
                .singleOrNull { row ->
                    Triple(row[tokens.expiresAt], row[tokens.usedAt], row[tokens.userId])
                } ?: return@transaction TokenVerificationResult.Invalid("Token not found")

            val (expiresAt, usedAt, userId) = resetToken

            val validation = TokenValidator.validate(
                expiresAt = expiresAt,
                usedAt = usedAt,
                now = nowLocal
            )

            if (!validation.isValid) {
                return@transaction TokenVerificationResult.Invalid(validation.reason!!)
            }

            TokenVerificationResult.Valid(userId)
        }

        when (result) {
            is TokenVerificationResult.Valid -> {
                eventBus?.publish(PasswordResetEvent.PasswordResetTokenVerified(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenVerificationResult.Invalid -> {
                eventBus?.publish(PasswordResetEvent.PasswordResetTokenVerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm,
                    reason = result.reason
                ))
            }
        }

        return result
    }

    override suspend fun consumeResetToken(token: String): TokenConsumptionResult {
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)

        val result = db.transaction {
            val hashedToken = TokenHasher.hash(token)
            val resetToken = select(tokens)
                .where {
                    (tokens.realmId eq realm) and (tokens.token eq hashedToken)
                }
                .forUpdate()
                .singleOrNull { row ->
                    Triple(row[tokens.expiresAt], row[tokens.usedAt], row[tokens.userId])
                } ?: return@transaction TokenConsumptionResult.Invalid("Token not found")

            val (expiresAt, usedAt, userId) = resetToken

            val validation = TokenValidator.validate(
                expiresAt = expiresAt,
                usedAt = usedAt,
                now = nowLocal
            )

            if (!validation.isValid) {
                return@transaction TokenConsumptionResult.Invalid(validation.reason!!)
            }

            update(tokens) {
                set(tokens.usedAt, nowLocal)
                where {
                    (tokens.realmId eq realm) and (tokens.token eq hashedToken)
                }
            }

            update(tokens) {
                set(tokens.usedAt, nowLocal)
                where {
                    (tokens.realmId eq realm) and
                    (tokens.userId eq userId) and
                    (tokens.token neq hashedToken) and
                    (tokens.usedAt.isNull())
                }
            }

            TokenConsumptionResult.Success(userId)
        }

        when (result) {
            is TokenConsumptionResult.Success -> {
                rateLimiter.clear("reset:user:${result.userId}")

                eventBus?.publish(PasswordResetEvent.PasswordResetCompleted(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenConsumptionResult.Invalid -> {
                eventBus?.publish(PasswordResetEvent.PasswordResetCompletionFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm,
                    reason = result.reason
                ))
            }
        }

        return result
    }

    override suspend fun revokeAllResetTokens(userId: UUID) {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

        db.transaction {
            update(tokens) {
                set(tokens.usedAt, now)
                where {
                    (tokens.realmId eq realm) and
                    (tokens.userId eq userId) and
                    (tokens.usedAt.isNull())
                }
            }
        }
    }
}
