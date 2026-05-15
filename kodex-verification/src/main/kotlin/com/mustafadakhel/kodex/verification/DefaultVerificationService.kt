package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.VerificationEvent
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
import com.mustafadakhel.kodex.verification.schema.VerificationSchema
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

internal class DefaultVerificationService(
    private val db: KodexDatabase,
    private val schema: VerificationSchema,
    private val config: VerificationConfig,
    private val timeZone: TimeZone,
    private val eventBus: EventBus?,
    private val realm: String,
    private val rateLimiter: RateLimiter
) : VerificationService {

    private val contacts = schema.verifiableContacts
    private val tokens = schema.verificationTokens

    override suspend fun setContact(userId: UUID, contactType: ContactType, value: String) {
        val typeKey = contactType.key

        db.transaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            val existing = contacts
                .selectAll()
                .where {
                    (contacts.realmId eq realm) and
                    (contacts.userId eq userId) and
                    (contacts.contactType eq typeKey)
                }
                .singleOrNull()

            if (existing != null) {
                val existingValue = existing[contacts.contactValue]
                val resetVerification = existingValue != value

                contacts.update({
                    (contacts.realmId eq realm) and
                    (contacts.userId eq userId) and
                    (contacts.contactType eq typeKey)
                }) {
                    it[contacts.contactValue] = value
                    it[contacts.isVerified] = if (resetVerification) false else existing[contacts.isVerified]
                    it[contacts.verifiedAt] = if (resetVerification) null else existing[contacts.verifiedAt]
                    it[contacts.updatedAt] = now
                }

                if (resetVerification) {
                    tokens.deleteWhere {
                        (tokens.realmId eq realm) and
                        (tokens.userId eq userId) and
                        (tokens.contactType eq typeKey)
                    }
                }
            } else {
                contacts.insert {
                    it[contacts.realmId] = realm
                    it[contacts.userId] = userId
                    it[contacts.contactType] = typeKey
                    it[contacts.contactValue] = value
                    it[contacts.isVerified] = false
                    it[contacts.verifiedAt] = null
                    it[contacts.updatedAt] = now
                }
            }
        }
    }

    override suspend fun removeContact(userId: UUID, contactType: ContactType) {
        val typeKey = contactType.key

        db.transaction {
            contacts.deleteWhere {
                (contacts.realmId eq realm) and
                (contacts.userId eq userId) and
                (contacts.contactType eq typeKey)
            }

            tokens.deleteWhere {
                (tokens.realmId eq realm) and
                (tokens.userId eq userId) and
                (tokens.contactType eq typeKey)
            }
        }
    }

    override fun getContact(userId: UUID, contactType: ContactType): ContactVerification? {
        val typeKey = contactType.key

        return db.transaction {
            contacts
                .selectAll()
                .where {
                    (contacts.realmId eq realm) and
                    (contacts.userId eq userId) and
                    (contacts.contactType eq typeKey)
                }
                .singleOrNull()
                ?.let {
                    ContactVerification(
                        contactType = contactType,
                        contactValue = it[contacts.contactValue],
                        isVerified = it[contacts.isVerified],
                        verifiedAt = it[contacts.verifiedAt]
                    )
                }
        }
    }

    override fun getUserContacts(userId: UUID): List<ContactVerification> {
        return db.transaction {
            contacts
                .selectAll()
                .where {
                    (contacts.realmId eq realm) and (contacts.userId eq userId)
                }
                .map {
                    ContactVerification(
                        contactType = ContactType.fromKey(it[contacts.contactType]),
                        contactValue = it[contacts.contactValue],
                        isVerified = it[contacts.isVerified],
                        verifiedAt = it[contacts.verifiedAt]
                    )
                }
        }
    }

    override fun isContactVerified(userId: UUID, contactType: ContactType): Boolean {
        return getContact(userId, contactType)?.isVerified ?: false
    }

    override fun canLogin(userId: UUID): Boolean {
        val requiredContacts = config.getRequiredContacts()

        if (requiredContacts.isEmpty()) {
            return true
        }

        return requiredContacts.all { type ->
            isContactVerified(userId, type)
        }
    }

    override fun getStatus(userId: UUID): UserVerificationStatus {
        val userContacts = getUserContacts(userId)
        val contactsMap = userContacts.associateBy { it.contactType.key }
        return UserVerificationStatus(userId, contactsMap)
    }

    override fun getMissingVerifications(userId: UUID): List<ContactType> {
        val requiredContacts = config.getRequiredContacts()
        return requiredContacts.filter { type ->
            !isContactVerified(userId, type)
        }
    }

    override suspend fun sendVerification(
        userId: UUID,
        contactType: ContactType,
        ipAddress: String?
    ): VerificationSendResult {
        val now = CurrentKotlinInstant

        val contact = getContact(userId, contactType)
            ?: error("Contact not found for user $userId: ${contactType.key}")

        val sender = config.getSender(contactType)
            ?: error("No sender configured for contact type: ${contactType.key}")

        val policy = config.getPolicy(contactType)
        if (policy != null && policy.dependsOn.isNotEmpty()) {
            val unmet = policy.dependsOn.filter { dep -> !isContactVerified(userId, dep) }
            if (unmet.isNotEmpty()) {
                val names = unmet.joinToString { it.key }
                return VerificationSendResult.DependencyNotMet(
                    missingDependencies = unmet,
                    message = "Cannot send verification for ${contactType.key}: unverified dependencies: $names"
                )
            }
        }

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

        val token = generateToken(contactType)

        try {
            sender.send(contact.contactValue, token)
        } catch (e: Exception) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(contactReservation.reservationId)
            rateLimiter.releaseReservation(ipReservation?.reservationId)

            eventBus?.publish(VerificationEvent.VerificationFailed(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realm,
                userId = userId,
                verificationType = contactType.key,
                reason = e.message ?: "Send failed"
            ))

            return VerificationSendResult.SendFailed(e.message ?: "Failed to send verification")
        }

        storeToken(userId, contactType, token)

        eventBus?.publish(VerificationEvent.EmailVerificationSent(
            eventId = UUID.randomUUID(),
            timestamp = now,
            realmId = realm,
            userId = userId,
            email = contact.contactValue
        ))

        return VerificationSendResult.Success
    }

    override suspend fun verifyToken(
        userId: UUID,
        contactType: ContactType,
        token: String,
        ipAddress: String?
    ): VerificationResult {
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)
        val startTime = System.nanoTime()
        val typeKey = contactType.key

        val rateLimitKey = "verify:attempt:user:$userId:ip:${ipAddress ?: "unknown"}"
        val userIpLimit = rateLimiter.checkLimit(
            key = rateLimitKey,
            limit = config.maxVerifyAttemptsPerUserIp,
            window = config.verifyRateLimitWindow
        )
        if (userIpLimit is RateLimitResult.Exceeded) {
            ensureMinimumResponseTime(startTime)
            return VerificationResult.RateLimitExceeded(userIpLimit.reason)
        }

        val hashedToken = TokenHasher.hash(token)

        val result = db.transaction {
            val tokenRecord = tokens
                .selectAll()
                .where {
                    (tokens.realmId eq realm) and
                    (tokens.userId eq userId) and
                    (tokens.token eq hashedToken) and
                    (tokens.contactType eq typeKey)
                }
                .singleOrNull()

            if (tokenRecord == null) {
                return@transaction VerificationResult.Invalid("Token not found")
            }

            val validation = TokenValidator.validate(
                expiresAt = tokenRecord[tokens.expiresAt],
                usedAt = tokenRecord[tokens.usedAt],
                now = nowLocal
            )

            if (!validation.isValid) {
                return@transaction VerificationResult.Invalid(validation.reason!!)
            }

            tokens.update({
                (tokens.realmId eq realm) and
                (tokens.userId eq userId) and
                (tokens.token eq hashedToken) and
                (tokens.contactType eq typeKey)
            }) {
                it[tokens.usedAt] = nowLocal
            }

            setVerified(userId, contactType, true)

            VerificationResult.Success
        }

        if (result is VerificationResult.Success) {
            rateLimiter.clear(rateLimitKey)
            rateLimiter.clear("verify:send:user:$userId")
        }

        when (result) {
            is VerificationResult.Success -> {
                val contactValue = getContact(userId, contactType)?.contactValue ?: ""
                when (contactType) {
                    is ContactType.Email -> {
                        eventBus?.publish(VerificationEvent.EmailVerified(
                            eventId = UUID.randomUUID(),
                            timestamp = now,
                            realmId = realm,
                            userId = userId,
                            email = contactValue
                        ))
                    }
                    is ContactType.Phone -> {
                        eventBus?.publish(VerificationEvent.PhoneVerified(
                            eventId = UUID.randomUUID(),
                            timestamp = now,
                            realmId = realm,
                            userId = userId,
                            phone = contactValue
                        ))
                    }
                    is ContactType.CustomAttribute -> {
                    }
                }
            }
            is VerificationResult.Invalid -> {
                eventBus?.publish(VerificationEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm,
                    userId = userId,
                    verificationType = contactType.key,
                    reason = result.reason
                ))
            }
            is VerificationResult.RateLimitExceeded -> {
            }
        }

        ensureMinimumResponseTime(startTime)

        return result
    }

    private suspend fun ensureMinimumResponseTime(startTimeNanos: Long) {
        val elapsedMs = (System.nanoTime() - startTimeNanos) / 1_000_000
        val minResponseTime = config.minVerificationResponseTimeMs

        if (elapsedMs < minResponseTime) {
            delay(minResponseTime - elapsedMs)
        }
    }

    override suspend fun resendVerification(
        userId: UUID,
        contactType: ContactType,
        ipAddress: String?
    ): VerificationSendResult {
        val typeKey = contactType.key

        db.transaction {
            tokens.deleteWhere {
                (tokens.realmId eq realm) and
                (tokens.userId eq userId) and
                (tokens.contactType eq typeKey)
            }
        }

        return sendVerification(userId, contactType, ipAddress)
    }

    override fun setVerified(userId: UUID, contactType: ContactType, verified: Boolean) {
        val typeKey = contactType.key

        db.transaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            contacts.update({
                (contacts.realmId eq realm) and
                (contacts.userId eq userId) and
                (contacts.contactType eq typeKey)
            }) {
                it[contacts.isVerified] = verified
                it[contacts.verifiedAt] = if (verified) now else null
                it[contacts.updatedAt] = now
            }
        }
    }

    private fun generateToken(contactType: ContactType): String {
        val format = config.getPolicy(contactType)?.tokenFormat ?: HexFormat()
        return TokenGenerator.generate(format)
    }

    private fun storeToken(userId: UUID, contactType: ContactType, token: String) {
        val typeKey = contactType.key

        db.transaction {
            val now = CurrentKotlinInstant
            val expiration = config.getTokenExpiration(contactType)
            val expiresAt = ExpirationCalculator.calculateExpiration(expiration, timeZone, now)

            tokens.insert {
                it[tokens.realmId] = realm
                it[tokens.userId] = userId
                it[tokens.contactType] = typeKey
                it[tokens.token] = TokenHasher.hash(token)
                it[tokens.expiresAt] = expiresAt
            }
        }
    }
}
