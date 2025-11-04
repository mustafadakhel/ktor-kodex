package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.tokens.security.RateLimitReservation
import com.mustafadakhel.kodex.tokens.security.RateLimitResult
import com.mustafadakhel.kodex.tokens.security.RateLimiter
import com.mustafadakhel.kodex.tokens.token.HexFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.tokens.token.TokenValidator
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetContacts
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetTokens
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
    private val eventBus: com.mustafadakhel.kodex.event.EventBus?,
    private val realm: String
) : PasswordResetService {

    private val rateLimiter = RateLimiter()

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

        val userInfo = kodexTransaction {
            val contact = PasswordResetContacts
                .selectAll()
                .where {
                    (PasswordResetContacts.contactType eq contactType.name) and
                    (PasswordResetContacts.contactValue eq identifier)
                }
                .singleOrNull()

            if (contact == null) {
                return@kodexTransaction null
            }

            val userId = contact[PasswordResetContacts.userId]

            val userReservation = rateLimiter.checkAndReserve(
                key = "reset:user:$userId",
                limit = config.rateLimit.maxAttemptsPerUser,
                window = config.rateLimit.window,
                cooldown = config.rateLimit.cooldown
            )
            if (!userReservation.isAllowed()) {
                return@kodexTransaction null
            }

            Triple(userId, userReservation.reservationId, true)
        }

        if (userInfo == null) {
            return PasswordResetResult.Success
        }

        val (userId, userReservationId, _) = userInfo

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

            eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetInitiationFailed(
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
                .where { PasswordResetTokens.token eq token }
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
                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetTokenVerified(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenVerificationResult.Invalid -> {
                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetTokenVerificationFailed(
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
                .where { PasswordResetTokens.token eq token }
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

            PasswordResetTokens.update({ PasswordResetTokens.token eq token }) {
                it[PasswordResetTokens.usedAt] = now
            }

            PasswordResetTokens.update({
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

                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetCompleted(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenConsumptionResult.Invalid -> {
                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetCompletionFailed(
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
                (PasswordResetTokens.userId eq userId) and
                (PasswordResetTokens.usedAt.isNull())
            }) {
                it[PasswordResetTokens.usedAt] = now
            }
        }
    }
}
