package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.tokens.security.RateLimitReservation
import com.mustafadakhel.kodex.tokens.security.RateLimitResult
import com.mustafadakhel.kodex.tokens.security.RateLimiter
import com.mustafadakhel.kodex.tokens.token.HexFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.tokens.token.TokenValidator
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.coroutines.delay
import com.mustafadakhel.kodex.verification.database.VerifiableContacts
import com.mustafadakhel.kodex.verification.database.VerificationTokens
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Default implementation of VerificationService.
 *
 * Stores verification status per contact type in the database
 * and generates tokens for verification workflows.
 */
internal class DefaultVerificationService(
    private val config: VerificationConfig,
    private val timeZone: TimeZone,
    private val eventBus: com.mustafadakhel.kodex.event.EventBus?,
    private val realm: String
) : VerificationService {

    private val rateLimiter = RateLimiter()

    // === Contact Management ===

    override suspend fun setContact(userId: UUID, identifier: ContactIdentifier, value: String) {
        kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            // Check if contact exists with different value
            val existing = VerifiableContacts
                .selectAll()
                .where {
                    (VerifiableContacts.userId eq userId) and
                    (VerifiableContacts.contactType eq identifier.type) and
                    (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
                }
                .singleOrNull()

            if (existing != null) {
                // Update existing contact
                val existingValue = existing[VerifiableContacts.contactValue]
                val resetVerification = existingValue != value

                VerifiableContacts.update({
                    (VerifiableContacts.userId eq userId) and
                    (VerifiableContacts.contactType eq identifier.type) and
                    (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
                }) {
                    it[VerifiableContacts.contactValue] = value
                    it[VerifiableContacts.isVerified] = if (resetVerification) false else existing[VerifiableContacts.isVerified]
                    it[VerifiableContacts.verifiedAt] = if (resetVerification) null else existing[VerifiableContacts.verifiedAt]
                    it[VerifiableContacts.updatedAt] = now
                }
            } else {
                // Insert new contact
                VerifiableContacts.insert {
                    it[VerifiableContacts.userId] = userId
                    it[VerifiableContacts.contactType] = identifier.type
                    it[VerifiableContacts.customAttributeKey] = identifier.customAttributeKey
                    it[VerifiableContacts.contactValue] = value
                    it[VerifiableContacts.isVerified] = false
                    it[VerifiableContacts.verifiedAt] = null
                    it[VerifiableContacts.updatedAt] = now
                }
            }
        }
    }

    override suspend fun removeContact(userId: UUID, identifier: ContactIdentifier) {
        kodexTransaction {
            VerifiableContacts.deleteWhere {
                (VerifiableContacts.userId eq userId) and
                (contactType eq identifier.type) and
                (customAttributeKey eq identifier.customAttributeKey)
            }

            // Also delete any tokens for this contact
            VerificationTokens.deleteWhere {
                (VerificationTokens.userId eq userId) and
                (contactType eq identifier.type) and
                (customAttributeKey eq identifier.customAttributeKey)
            }
        }
    }

    override fun getContact(userId: UUID, identifier: ContactIdentifier): ContactVerification? {
        return kodexTransaction {
            VerifiableContacts
                .selectAll()
                .where {
                    (VerifiableContacts.userId eq userId) and
                    (VerifiableContacts.contactType eq identifier.type) and
                    (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
                }
                .singleOrNull()
                ?.let {
                    ContactVerification(
                        identifier = identifier,
                        contactValue = it[VerifiableContacts.contactValue],
                        isVerified = it[VerifiableContacts.isVerified],
                        verifiedAt = it[VerifiableContacts.verifiedAt]
                    )
                }
        }
    }

    override fun getUserContacts(userId: UUID): List<ContactVerification> {
        return kodexTransaction {
            VerifiableContacts
                .selectAll()
                .where { VerifiableContacts.userId eq userId }
                .map {
                    val type = it[VerifiableContacts.contactType]
                    val attrKey = it[VerifiableContacts.customAttributeKey]
                    val identifier = ContactIdentifier(type, attrKey)

                    ContactVerification(
                        identifier = identifier,
                        contactValue = it[VerifiableContacts.contactValue],
                        isVerified = it[VerifiableContacts.isVerified],
                        verifiedAt = it[VerifiableContacts.verifiedAt]
                    )
                }
        }
    }

    // === Verification Status ===

    override fun isContactVerified(userId: UUID, identifier: ContactIdentifier): Boolean {
        return getContact(userId, identifier)?.isVerified ?: false
    }

    override fun canLogin(userId: UUID): Boolean {
        val requiredContacts = config.getRequiredContacts()

        if (requiredContacts.isEmpty()) {
            // No verification required
            return true
        }

        // Check if all required contacts are verified
        return requiredContacts.all { identifier ->
            isContactVerified(userId, identifier)
        }
    }

    override fun getStatus(userId: UUID): UserVerificationStatus {
        val contacts = getUserContacts(userId)
        val contactsMap = contacts.associateBy { it.identifier.key }
        return UserVerificationStatus(userId, contactsMap)
    }

    override fun getMissingVerifications(userId: UUID): List<ContactIdentifier> {
        val requiredContacts = config.getRequiredContacts()
        return requiredContacts.filter { identifier ->
            !isContactVerified(userId, identifier)
        }
    }

    // === Token Operations ===

    override suspend fun sendVerification(
        userId: UUID,
        identifier: ContactIdentifier,
        ipAddress: String?
    ): VerificationSendResult {
        val contact = getContact(userId, identifier)
            ?: error("Contact not found for user $userId: ${identifier.key}")

        // Get registered sender from config
        val sender = config.getSender(identifier)
            ?: error("No sender configured for contact type: ${identifier.key}")

        // Check rate limits (3-dimensional: user, contact value, IP)
        // Use checkAndReserve for two-phase commit pattern with optional cooldown
        val userReservation = rateLimiter.checkAndReserve(
            key = "verify:send:user:$userId",
            limit = config.maxSendAttemptsPerUser,
            window = config.sendRateLimitWindow,
            cooldown = config.sendCooldownPeriod
        )
        if (!userReservation.isAllowed()) {
            val reason = when (val result = userReservation.result) {
                is RateLimitResult.Exceeded -> result.reason
                is RateLimitResult.Cooldown -> result.reason
                else -> "Rate limit check failed"
            }
            return VerificationSendResult.RateLimitExceeded(reason)
        }

        val contactReservation = rateLimiter.checkAndReserve(
            key = "verify:send:contact:${contact.contactValue}",
            limit = config.maxSendAttemptsPerContact,
            window = config.sendRateLimitWindow,
            cooldown = config.sendCooldownPeriod
        )
        if (!contactReservation.isAllowed()) {
            // Release user reservation
            rateLimiter.releaseReservation(userReservation.reservationId)
            val reason = when (val result = contactReservation.result) {
                is RateLimitResult.Exceeded -> result.reason
                is RateLimitResult.Cooldown -> result.reason
                else -> "Rate limit check failed"
            }
            return VerificationSendResult.RateLimitExceeded(reason)
        }

        var ipReservation: RateLimitReservation? = null
        if (ipAddress != null) {
            ipReservation = rateLimiter.checkAndReserve(
                key = "verify:send:ip:$ipAddress",
                limit = config.maxSendAttemptsPerIp,
                window = config.sendRateLimitWindow,
                cooldown = config.sendCooldownPeriod
            )
            if (!ipReservation.isAllowed()) {
                // Release previous reservations
                rateLimiter.releaseReservation(userReservation.reservationId)
                rateLimiter.releaseReservation(contactReservation.reservationId)
                val reason = when (val result = ipReservation.result) {
                    is RateLimitResult.Exceeded -> result.reason
                    is RateLimitResult.Cooldown -> result.reason
                    else -> "Rate limit check failed"
                }
                return VerificationSendResult.RateLimitExceeded(reason)
            }
        }

        // Generate token but DON'T store yet
        val token = generateToken()

        // Try to send FIRST
        try {
            sender.send(contact.contactValue, token)
        } catch (e: Exception) {
            // Release ALL reservations on send failure
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(contactReservation.reservationId)
            rateLimiter.releaseReservation(ipReservation?.reservationId)

            // Emit failure event
            eventBus?.publish(com.mustafadakhel.kodex.event.VerificationEvent.VerificationFailed(
                eventId = UUID.randomUUID(),
                timestamp = kotlinx.datetime.Clock.System.now(),
                realmId = realm,
                userId = userId,
                verificationType = identifier.type.name,
                reason = e.message ?: "Send failed"
            ))

            // Return SendFailed so user can retry
            return VerificationSendResult.SendFailed(e.message ?: "Failed to send verification")
        }

        // Send succeeded - NOW store token and keep rate limit reservations
        storeToken(userId, identifier, token)

        // Emit success event
        eventBus?.publish(com.mustafadakhel.kodex.event.VerificationEvent.EmailVerificationSent(
            eventId = UUID.randomUUID(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            realmId = realm,
            userId = userId,
            email = contact.contactValue,
            verificationCode = null
        ))

        return VerificationSendResult.Success(token)
    }

    override suspend fun verifyToken(
        userId: UUID,
        identifier: ContactIdentifier,
        token: String,
        ipAddress: String?
    ): VerificationResult {
        val startTime = System.nanoTime()

        // SECURITY: Rate limit on (userId + IP) instead of token to prevent token enumeration
        // Using token in rate limit key would reveal which tokens exist in database
        val rateLimitKey = "verify:attempt:user:$userId:ip:${ipAddress ?: "unknown"}"
        val userIpLimit = rateLimiter.checkLimit(
            key = rateLimitKey,
            limit = config.maxVerifyAttemptsPerUserIp,
            window = config.verifyRateLimitWindow
        )
        if (userIpLimit is RateLimitResult.Exceeded) {
            // Add constant-time delay before returning
            ensureMinimumResponseTime(startTime)
            return VerificationResult.RateLimitExceeded(userIpLimit.reason)
        }

        val result = kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            val tokenRecord = VerificationTokens
                .selectAll()
                .where {
                    (VerificationTokens.userId eq userId) and
                    (VerificationTokens.token eq token) and
                    (VerificationTokens.contactType eq identifier.type) and
                    (VerificationTokens.customAttributeKey eq identifier.customAttributeKey)
                }
                .singleOrNull()

            if (tokenRecord == null) {
                return@kodexTransaction VerificationResult.Invalid("Token not found")
            }

            // Validate token using common utility
            val validation = TokenValidator.validate(
                expiresAt = tokenRecord[VerificationTokens.expiresAt],
                usedAt = tokenRecord[VerificationTokens.usedAt],
                now = now
            )

            if (!validation.isValid) {
                return@kodexTransaction VerificationResult.Invalid(validation.reason!!)
            }

            // Mark token as used
            VerificationTokens.update({ VerificationTokens.token eq token }) {
                it[VerificationTokens.usedAt] = now
            }

            // Mark contact as verified
            setVerified(userId, identifier, true)

            // Clear rate limits after successful verification
            rateLimiter.clear(rateLimitKey)
            rateLimiter.clear("verify:send:user:$userId")

            VerificationResult.Success
        }

        // Emit events after transaction completes
        when (result) {
            is VerificationResult.Success -> {
                val contactValue = getContact(userId, identifier)?.contactValue ?: ""
                when (identifier.type) {
                    ContactType.EMAIL -> {
                        eventBus?.publish(com.mustafadakhel.kodex.event.VerificationEvent.EmailVerified(
                            eventId = UUID.randomUUID(),
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            realmId = realm,
                            userId = userId,
                            email = contactValue
                        ))
                    }
                    ContactType.PHONE -> {
                        eventBus?.publish(com.mustafadakhel.kodex.event.VerificationEvent.PhoneVerified(
                            eventId = UUID.randomUUID(),
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            realmId = realm,
                            userId = userId,
                            phone = contactValue
                        ))
                    }
                    ContactType.CUSTOM_ATTRIBUTE -> {
                        // Use generic VerificationEvent for custom attributes
                        // Could extend VerificationEvent with CustomAttributeVerified if needed
                    }
                }
            }
            is VerificationResult.Invalid -> {
                eventBus?.publish(com.mustafadakhel.kodex.event.VerificationEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = kotlinx.datetime.Clock.System.now(),
                    realmId = realm,
                    userId = userId,
                    verificationType = identifier.type.name,
                    reason = result.reason
                ))
            }
            is VerificationResult.RateLimitExceeded -> {
                // Rate limit exceeded, no additional event needed
            }
        }

        // SECURITY: Add constant-time delay to prevent timing attacks
        // Ensures both valid and invalid tokens take similar time to verify
        ensureMinimumResponseTime(startTime)

        return result
    }

    /**
     * Ensures minimum response time to prevent timing attacks.
     * Adds delay if operation completed faster than minimum.
     */
    private suspend fun ensureMinimumResponseTime(startTimeNanos: Long) {
        val elapsedMs = (System.nanoTime() - startTimeNanos) / 1_000_000
        val minResponseTime = config.minVerificationResponseTimeMs

        if (elapsedMs < minResponseTime) {
            delay(minResponseTime - elapsedMs)
        }
    }

    override suspend fun resendVerification(
        userId: UUID,
        identifier: ContactIdentifier,
        ipAddress: String?
    ): VerificationSendResult {
        // Invalidate existing tokens for this contact
        kodexTransaction {
            VerificationTokens.deleteWhere {
                (VerificationTokens.userId eq userId) and
                (contactType eq identifier.type) and
                (customAttributeKey eq identifier.customAttributeKey)
            }
        }

        // Send new verification (includes rate limiting)
        return sendVerification(userId, identifier, ipAddress)
    }

    // === Manual Control ===

    override fun setVerified(userId: UUID, identifier: ContactIdentifier, verified: Boolean) {
        kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            VerifiableContacts.update({
                (VerifiableContacts.userId eq userId) and
                (VerifiableContacts.contactType eq identifier.type) and
                (VerifiableContacts.customAttributeKey eq identifier.customAttributeKey)
            }) {
                it[VerifiableContacts.isVerified] = verified
                it[VerifiableContacts.verifiedAt] = if (verified) now else null
                it[VerifiableContacts.updatedAt] = now
            }
        }
    }

    // === Private Helpers ===

    private fun generateToken(): String = TokenGenerator.generate(HexFormat())

    private fun storeToken(userId: UUID, identifier: ContactIdentifier, token: String) {
        kodexTransaction {
            val now = Clock.System.now()
            // Use per-contact expiration, falling back to default
            val expiration = config.getTokenExpiration(identifier)
            val expiresAt = ExpirationCalculator.calculateExpiration(expiration, timeZone, now)

            VerificationTokens.insert {
                it[VerificationTokens.userId] = userId
                it[contactType] = identifier.type
                it[customAttributeKey] = identifier.customAttributeKey
                it[VerificationTokens.token] = token
                it[VerificationTokens.expiresAt] = expiresAt
            }
        }
    }
}
