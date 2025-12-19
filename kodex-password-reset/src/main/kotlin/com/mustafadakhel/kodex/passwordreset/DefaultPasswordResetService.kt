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
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
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
        // SECURITY: Check rate limits BEFORE database query to prevent user enumeration
        // Rate limit on identifier and IP first (not user, since we don't know user yet)
        // Use checkAndReserve for two-phase commit pattern
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
                // Release identifier reservation
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

        // Generate token outside transaction
        val token = TokenGenerator.generate(HexFormat())
        val expiresAt = ExpirationCalculator.calculateExpiration(config.tokenValidity, timeZone, clockNow)

        // Look up user FIRST (don't create token yet)
        val userInfo = kodexTransaction {
            // Find user by contact identifier in password reset contacts table
            val contact = PasswordResetContacts
                .selectAll()
                .where {
                    (PasswordResetContacts.contactType eq contactType.name) and
                    (PasswordResetContacts.contactValue eq identifier)
                }
                .singleOrNull()

            // SECURITY: If user not found, return null (will release reservations and return Success)
            if (contact == null) {
                return@kodexTransaction null
            }

            val userId = contact[PasswordResetContacts.userId]

            // Check user-specific rate limit
            val userReservation = rateLimiter.checkAndReserve(
                key = "reset:user:$userId",
                limit = config.rateLimit.maxAttemptsPerUser,
                window = config.rateLimit.window,
                cooldown = config.rateLimit.cooldown
            )
            if (!userReservation.isAllowed()) {
                // Even for rate limited users, return null to avoid enumeration
                // Will release reservations and return Success
                return@kodexTransaction null
            }

            // Return user info for sending
            Triple(userId, userReservation.reservationId, true)
        }

        // If user not found or rate limited, return Success WITHOUT releasing reservations (enumeration prevention)
        if (userInfo == null) {
            // SECURITY: Do NOT release reservations here!
            // Releasing reservations would allow unlimited requests for non-existent users,
            // enabling account enumeration via rate limit timing differences.
            // Keep rate limits enforced even for non-existent users.
            // SECURITY: Return Success even though nothing was sent (prevents enumeration)
            return PasswordResetResult.Success
        }

        val (userId, userReservationId, _) = userInfo

        // Try to send FIRST
        try {
            passwordResetSender.send(
                recipient = identifier,
                token = token,
                expiresAt = expiresAt.toString()
            )
        } catch (e: Exception) {
            // Release ALL reservations on send failure
            rateLimiter.releaseReservation(identifierReservation.reservationId)
            rateLimiter.releaseReservation(ipReservation?.reservationId)
            rateLimiter.releaseReservation(userReservationId)

            // Emit failure event
            eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetInitiationFailed(
                eventId = UUID.randomUUID(),
                timestamp = clockNow,
                realmId = realm,
                contactType = contactType.name,
                contactValue = identifier,
                reason = e.message ?: "Send failed"
            ))

            // SECURITY: Return Success even on send failure (prevents enumeration)
            // User is NOT rate limited, can retry
            return PasswordResetResult.Success
        }

        // Send succeeded - NOW store token in database
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

        // Emit success event
        eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetInitiated(
            eventId = UUID.randomUUID(),
            timestamp = clockNow,
            realmId = realm,
            userId = userId,
            contactType = contactType.name,
            contactValue = identifier
        ))

        // SECURITY: Always return Success, whether user exists or not
        // Prevents user enumeration via different response messages
        return PasswordResetResult.Success
    }

    override suspend fun verifyResetToken(token: String): TokenVerificationResult {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

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

        // Emit events
        when (result) {
            is TokenVerificationResult.Valid -> {
                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetTokenVerified(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenVerificationResult.Invalid -> {
                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetTokenVerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm,
                    reason = result.reason
                ))
            }
        }

        return result
    }

    override suspend fun consumeResetToken(token: String): TokenConsumptionResult {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

        val result = kodexTransaction {
            // Verify token
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

            // Mark token as used
            PasswordResetTokens.update({ PasswordResetTokens.token eq token }) {
                it[PasswordResetTokens.usedAt] = now
            }

            // Revoke all other reset tokens for this user
            PasswordResetTokens.update({
                (PasswordResetTokens.userId eq userId) and
                (PasswordResetTokens.token neq token) and
                (PasswordResetTokens.usedAt.isNull())
            }) {
                it[PasswordResetTokens.usedAt] = now
            }

            TokenConsumptionResult.Success(userId)
        }

        // Emit events and clear rate limits after transaction commits successfully
        when (result) {
            is TokenConsumptionResult.Success -> {
                rateLimiter.clear("reset:user:${result.userId}")

                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetCompleted(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm,
                    userId = result.userId
                ))
            }
            is TokenConsumptionResult.Invalid -> {
                eventBus?.publish(com.mustafadakhel.kodex.event.PasswordResetEvent.PasswordResetCompletionFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm,
                    reason = result.reason
                ))
            }
        }

        return result
    }

    override suspend fun revokeAllResetTokens(userId: UUID) {
        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

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
