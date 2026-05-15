package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.PasswordResetEvent
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
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
            val contact = contacts
                .selectAll()
                .where {
                    (contacts.realmId eq realm) and
                    (contacts.contactType eq contactType.name) and
                    (contacts.contactValue eq identifier)
                }
                .singleOrNull()

            contact?.get(contacts.userId)?.value
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

        try {
            passwordResetSender.send(
                recipient = identifier,
                token = token,
                expiresAt = expiresAt.toString()
            )
        } catch (e: Exception) {
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

            return PasswordResetResult.Success
        }

        db.transaction {
            tokens.insert {
                it[tokens.realmId] = realm
                it[tokens.userId] = userId
                it[tokens.token] = TokenHasher.hash(token)
                it[tokens.contactValue] = identifier
                it[tokens.createdAt] = now
                it[tokens.expiresAt] = expiresAt
                it[tokens.usedAt] = null
                it[tokens.ipAddress] = ipAddress
            }
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
            val resetToken = tokens
                .selectAll()
                .where {
                    (tokens.realmId eq realm) and (tokens.token eq TokenHasher.hash(token))
                }
                .singleOrNull() ?: return@transaction TokenVerificationResult.Invalid("Token not found")

            val validation = TokenValidator.validate(
                expiresAt = resetToken[tokens.expiresAt],
                usedAt = resetToken[tokens.usedAt],
                now = nowLocal
            )

            if (!validation.isValid) {
                return@transaction TokenVerificationResult.Invalid(validation.reason!!)
            }

            TokenVerificationResult.Valid(resetToken[tokens.userId].value)
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
            val resetToken = tokens
                .selectAll()
                .where {
                    (tokens.realmId eq realm) and (tokens.token eq hashedToken)
                }
                .singleOrNull() ?: return@transaction TokenConsumptionResult.Invalid("Token not found")

            val validation = TokenValidator.validate(
                expiresAt = resetToken[tokens.expiresAt],
                usedAt = resetToken[tokens.usedAt],
                now = nowLocal
            )

            if (!validation.isValid) {
                return@transaction TokenConsumptionResult.Invalid(validation.reason!!)
            }

            val userId = resetToken[tokens.userId].value

            tokens.update({
                (tokens.realmId eq realm) and (tokens.token eq hashedToken)
            }) {
                it[tokens.usedAt] = nowLocal
            }

            tokens.update({
                (tokens.realmId eq realm) and
                (tokens.userId eq userId) and
                (tokens.token neq hashedToken) and
                (tokens.usedAt.isNull())
            }) {
                it[tokens.usedAt] = nowLocal
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
            tokens.update({
                (tokens.realmId eq realm) and
                (tokens.userId eq userId) and
                (tokens.usedAt.isNull())
            }) {
                it[tokens.usedAt] = now
            }
        }
    }
}
