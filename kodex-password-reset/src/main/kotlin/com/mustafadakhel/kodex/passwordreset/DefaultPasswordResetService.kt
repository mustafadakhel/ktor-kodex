package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.PasswordResetEvent
import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.ratelimit.RateLimitReservation
import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.tokens.token.HexFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.tokens.token.TokenValidator
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetContacts
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetTokens
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Default implementation of password reset service.
 */
internal class DefaultPasswordResetService(
    private val config: PasswordResetConfigData,
    private val passwordResetSender: PasswordResetSender,
    private val timeZone: TimeZone,
    private val eventBus: EventBus?,
    private val realm: String,
    private val rateLimiter: RateLimiter
) : PasswordResetService {

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

        val clockNow = Clock.System.now()
        val now = clockNow.toLocalDateTime(timeZone)

        val token = TokenGenerator.generate(HexFormat())
        val expiresAt = ExpirationCalculator.calculateExpiration(config.tokenValidity, timeZone, clockNow)

        // First, get userId from database
        val userId = kodexTransaction {
            val contact = PasswordResetContacts
                .selectAll()
                .where {
                    (PasswordResetContacts.realmId eq realm) and
                    (PasswordResetContacts.contactType eq contactType.name) and
                    (PasswordResetContacts.contactValue eq identifier)
                }
                .singleOrNull()

            contact?.get(PasswordResetContacts.userId)
        }

        if (userId == null) {
            // No matching contact - simulate success for security (don't reveal if identifier exists)
            return PasswordResetResult.Success
        }

        // Check user rate limit outside transaction
        val userReservation = rateLimiter.checkAndReserve(
            key = "reset:user:$userId",
            limit = config.rateLimit.maxAttemptsPerUser,
            window = config.rateLimit.window,
            cooldown = config.rateLimit.cooldown
        )
        if (!userReservation.isAllowed()) {
            // Release previous reservations
            rateLimiter.releaseReservation(identifierReservation.reservationId)
            rateLimiter.releaseReservation(ipReservation?.reservationId)
            // Simulate success for security
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

        kodexTransaction {
            PasswordResetTokens.insert {
                it[PasswordResetTokens.realmId] = realm
                it[PasswordResetTokens.userId] = userId
                it[PasswordResetTokens.token] = token
                it[PasswordResetTokens.contactValue] = identifier
                it[PasswordResetTokens.createdAt] = now
                it[PasswordResetTokens.expiresAt] = expiresAt
                it[PasswordResetTokens.usedAt] = null
                it[PasswordResetTokens.ipAddress] = ipAddress
            }
        }

        eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetInitiated(
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
        val now = Clock.System.now().toLocalDateTime(timeZone)

        val result = kodexTransaction {
            val resetToken = PasswordResetTokens
                .selectAll()
                .where {
                    (PasswordResetTokens.realmId eq realm) and (PasswordResetTokens.token eq token)
                }
                .singleOrNull() ?: return@kodexTransaction TokenVerificationResult.Invalid("Token not found")

            val validation = TokenValidator.validate(
                expiresAt = resetToken[PasswordResetTokens.expiresAt],
                usedAt = resetToken[PasswordResetTokens.usedAt],
                now = now
            )

            if (!validation.isValid) {
                return@kodexTransaction TokenVerificationResult.Invalid(validation.reason!!)
            }

            TokenVerificationResult.Valid(resetToken[PasswordResetTokens.userId])
        }

        when (result) {
            is TokenVerificationResult.Valid -> {
                eventBus?.publish(PasswordResetEvent.PasswordResetTokenVerified(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenVerificationResult.Invalid -> {
                eventBus?.publish(PasswordResetEvent.PasswordResetTokenVerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm,
                    reason = result.reason
                ))
            }
        }

        return result
    }

    override suspend fun consumeResetToken(token: String): TokenConsumptionResult {
        val now = Clock.System.now().toLocalDateTime(timeZone)

        val result = kodexTransaction {
            val resetToken = PasswordResetTokens
                .selectAll()
                .where {
                    (PasswordResetTokens.realmId eq realm) and (PasswordResetTokens.token eq token)
                }
                .singleOrNull() ?: return@kodexTransaction TokenConsumptionResult.Invalid("Token not found")

            val validation = TokenValidator.validate(
                expiresAt = resetToken[PasswordResetTokens.expiresAt],
                usedAt = resetToken[PasswordResetTokens.usedAt],
                now = now
            )

            if (!validation.isValid) {
                return@kodexTransaction TokenConsumptionResult.Invalid(validation.reason!!)
            }

            val userId = resetToken[PasswordResetTokens.userId]

            PasswordResetTokens.update({
                (PasswordResetTokens.realmId eq realm) and (PasswordResetTokens.token eq token)
            }) {
                it[PasswordResetTokens.usedAt] = now
            }

            PasswordResetTokens.update({
                (PasswordResetTokens.realmId eq realm) and
                (PasswordResetTokens.userId eq userId) and
                (PasswordResetTokens.token neq token) and
                (PasswordResetTokens.usedAt.isNull())
            }) {
                it[PasswordResetTokens.usedAt] = now
            }

            TokenConsumptionResult.Success(userId)
        }

        when (result) {
            is TokenConsumptionResult.Success -> {
                rateLimiter.clear("reset:user:${result.userId}")

                eventBus?.publish(PasswordResetEvent.PasswordResetCompleted(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenConsumptionResult.Invalid -> {
                eventBus?.publish(PasswordResetEvent.PasswordResetCompletionFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm,
                    reason = result.reason
                ))
            }
        }

        return result
    }

    override suspend fun revokeAllResetTokens(userId: UUID) {
        val now = Clock.System.now().toLocalDateTime(timeZone)

        kodexTransaction {
            PasswordResetTokens.update({
                (PasswordResetTokens.realmId eq realm) and
                (PasswordResetTokens.userId eq userId) and
                (PasswordResetTokens.usedAt.isNull())
            }) {
                it[PasswordResetTokens.usedAt] = now
            }
        }
    }
}
