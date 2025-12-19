package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.FailureReason
import com.mustafadakhel.kodex.mfa.database.MfaBackupCodes
import com.mustafadakhel.kodex.mfa.database.MfaChallenges
import com.mustafadakhel.kodex.mfa.database.MfaMethodType
import com.mustafadakhel.kodex.mfa.database.MfaMethods
import com.mustafadakhel.kodex.mfa.database.MfaTotpUsedCodes
import com.mustafadakhel.kodex.mfa.database.MfaTrustedDevices
import com.mustafadakhel.kodex.mfa.device.DeviceFingerprint
import com.mustafadakhel.kodex.mfa.encryption.EncryptedSecret
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.event.MfaEvent
import com.mustafadakhel.kodex.mfa.totp.QrCodeGenerator
import com.mustafadakhel.kodex.mfa.totp.TotpGenerator
import com.mustafadakhel.kodex.mfa.totp.TotpValidator
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.tokens.token.AlphanumericFormat
import com.mustafadakhel.kodex.tokens.token.NumericFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.coroutines.delay
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class DefaultMfaService(
    private val config: MfaConfig,
    private val timeZone: TimeZone,
    private val hashingService: HashingService,
    private val secretEncryption: SecretEncryption,
    private val eventBus: EventBus,
    private val realmId: String,
    private val rateLimiter: RateLimiter,
    private val sessionStore: com.mustafadakhel.kodex.mfa.session.MfaSessionStore
) : MfaService {

    private val totpGenerator = TotpGenerator(
        algorithm = config.totpAlgorithm,
        digits = config.totpDigits,
        period = config.totpPeriod
    )
    private val totpValidator = TotpValidator(totpGenerator, config.totpTimeStepWindow)
    private val qrCodeGenerator = QrCodeGenerator()

    /**
     * Verifies that the given user has admin role.
     * @throws KodexThrowable.Authorization.InsufficientPermissions if user doesn't have admin role
     */
    private suspend fun requireAdminRole(userId: UUID) {
        val hasAdminRole = config.userHasRole?.invoke(userId, "admin") ?: false
        if (!hasAdminRole) {
            throw KodexThrowable.Authorization.InsufficientPermissions(
                requiredRole = "admin",
                userId = userId
            )
        }
    }

    override suspend fun enrollEmail(
        userId: UUID,
        email: String,
        ipAddress: String?
    ): EnrollmentResult {
        eventBus.publish(MfaEvent.EnrollmentStarted(
            eventId = UUID.randomUUID(),
            timestamp = CurrentKotlinInstant,
            realmId = realmId,
            userId = userId,
            methodType = MfaMethodType.EMAIL,
            contactValue = email,
            sourceIp = ipAddress
        ))

        val userKey = "mfa:enroll:user:$userId"
        val emailKey = "mfa:enroll:email:$email"
        val ipKey = ipAddress?.let { "mfa:enroll:ip:$it" }

        val userReservation = rateLimiter.checkAndReserve(
            userKey,
            config.maxEnrollAttemptsPerUser,
            config.enrollRateLimitWindow,
            config.enrollCooldownPeriod
        )

        if (!userReservation.isAllowed()) {
            val result = when (val rateLimitResult = userReservation.result) {
                is RateLimitResult.Exceeded -> EnrollmentResult.RateLimitExceeded(rateLimitResult.reason, null)
                is RateLimitResult.Cooldown -> EnrollmentResult.Cooldown(rateLimitResult.reason, rateLimitResult.retryAfter!!)
                else -> EnrollmentResult.Failed("Rate limit check failed")
            }
            eventBus.publish(MfaEvent.RateLimitExceeded(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                operation = "enrollment",
                reason = when (val r = userReservation.result) {
                    is RateLimitResult.Exceeded -> r.reason
                    is RateLimitResult.Cooldown -> r.reason
                    else -> "Rate limit check failed"
                },
                retryAfter = (userReservation.result as? RateLimitResult.Cooldown)?.retryAfter,
                sourceIp = ipAddress
            ))
            return result
        }

        val emailReservation = rateLimiter.checkAndReserve(
            emailKey,
            config.maxEnrollAttemptsPerContact,
            config.enrollRateLimitWindow
        )

        if (!emailReservation.isAllowed()) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            val result = when (val rateLimitResult = emailReservation.result) {
                is RateLimitResult.Exceeded -> EnrollmentResult.RateLimitExceeded(rateLimitResult.reason, null)
                else -> EnrollmentResult.Failed("Rate limit check failed")
            }
            eventBus.publish(MfaEvent.RateLimitExceeded(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                operation = "enrollment",
                reason = when (val r = emailReservation.result) {
                    is RateLimitResult.Exceeded -> r.reason
                    else -> "Rate limit check failed"
                },
                retryAfter = null,
                sourceIp = ipAddress
            ))
            return result
        }

        val ipReservation = ipKey?.let {
            rateLimiter.checkAndReserve(
                it,
                config.maxEnrollAttemptsPerIp,
                config.enrollRateLimitWindow
            )
        }

        if (ipReservation != null && !ipReservation.isAllowed()) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(emailReservation.reservationId)
            val result = when (val rateLimitResult = ipReservation.result) {
                is RateLimitResult.Exceeded -> EnrollmentResult.RateLimitExceeded(rateLimitResult.reason, null)
                else -> EnrollmentResult.Failed("Rate limit check failed")
            }
            eventBus.publish(MfaEvent.RateLimitExceeded(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                operation = "enrollment",
                reason = when (val r = ipReservation.result) {
                    is RateLimitResult.Exceeded -> r.reason
                    else -> "Rate limit check failed"
                },
                retryAfter = null,
                sourceIp = ipAddress
            ))
            return result
        }

        val code = TokenGenerator.generate(NumericFormat(config.codeLength))

        try {
            config.emailSender?.send(email, code)
        } catch (e: Exception) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(emailReservation.reservationId)
            ipReservation?.let { rateLimiter.releaseReservation(it.reservationId) }
            eventBus.publish(MfaEvent.EnrollmentFailed(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                reason = "Failed to send code: ${e.message}",
                failureReason = FailureReason.UNKNOWN,
                sourceIp = ipAddress
            ))
            return EnrollmentResult.Failed("Failed to send code: ${e.message}")
        }

        val challengeId = kodexTransaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, CurrentKotlinInstant)
            val codeHash = hashingService.hash(code)

            // Create the MFA method record (inactive) to store the email
            val methodId = MfaMethods.insertAndGetId {
                it[MfaMethods.realmId] = this@DefaultMfaService.realmId
                it[MfaMethods.userId] = userId
                it[MfaMethods.methodType] = MfaMethodType.EMAIL
                it[MfaMethods.identifier] = email
                it[MfaMethods.encryptedSecret] = null
                it[MfaMethods.encryptionNonce] = null
                it[MfaMethods.isActive] = false  // Inactive until verification
                it[MfaMethods.isPrimary] = false
                it[MfaMethods.enrolledAt] = now
            }.value

            MfaChallenges.insert {
                it[MfaChallenges.realmId] = this@DefaultMfaService.realmId
                it[MfaChallenges.userId] = userId
                it[MfaChallenges.methodId] = methodId
                it[MfaChallenges.codeHash] = codeHash
                it[MfaChallenges.expiresAt] = expiresAt
                it[MfaChallenges.createdAt] = now
                it[MfaChallenges.attempts] = 0
                it[MfaChallenges.maxAttempts] = config.maxVerifyAttempts
            }[MfaChallenges.id].value
        }

        return EnrollmentResult.CodeSent(challengeId)
    }

    override suspend fun verifyEmailEnrollment(
        userId: UUID,
        challengeId: UUID,
        code: String
    ): EnrollmentVerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            val challenge = kodexTransaction {
                MfaChallenges
                    .selectAll()
                    .where { (MfaChallenges.realmId eq realmId) and (MfaChallenges.id eq challengeId) }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid challenge")

            val userId = challenge[MfaChallenges.userId]
            val attempts = challenge[MfaChallenges.attempts]
            val maxAttempts = challenge[MfaChallenges.maxAttempts]
            val expiresAt = challenge[MfaChallenges.expiresAt]
            val verifiedAt = challenge[MfaChallenges.verifiedAt]
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            if (verifiedAt != null) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Challenge already verified")
            }

            if (now > expiresAt) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Expired("Code has expired")
            }

            if (attempts >= maxAttempts) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.RateLimitExceeded("Too many attempts")
            }

            val codeHash = challenge[MfaChallenges.codeHash]
            val isValid = hashingService.verify(code, codeHash)

            if (!isValid) {
                kodexTransaction {
                    MfaChallenges.update({
                        (MfaChallenges.realmId eq realmId) and (MfaChallenges.id eq challengeId)
                    }) {
                        it[MfaChallenges.attempts] = attempts + 1
                    }
                }
                eventBus.publish(MfaEvent.EnrollmentFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodType = MfaMethodType.EMAIL,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS
                ))
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            val methodId = kodexTransaction {
                MfaChallenges.update({
                    (MfaChallenges.realmId eq realmId) and (MfaChallenges.id eq challengeId)
                }) {
                    it[MfaChallenges.verifiedAt] = now
                }

                // Activate the method that was created during enrollment
                val methodId = challenge[MfaChallenges.methodId]
                val hadAnyMethod = hasAnyMethod(userId)
                MfaMethods.update({
                    (MfaMethods.realmId eq realmId) and (MfaMethods.id eq methodId)
                }) {
                    it[MfaMethods.isActive] = true
                    it[MfaMethods.isPrimary] = !hadAnyMethod
                }

                methodId
            }

            val backupCodes = generateBackupCodes(userId)

            eventBus.publish(MfaEvent.EnrollmentCompleted(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = MfaMethodType.EMAIL,
                isPrimary = !hasAnyMethod(userId)
            ))

            EnrollmentVerificationResult.Success(backupCodes)
        }
    }

    override suspend fun enrollTotp(userId: UUID, accountName: String): TotpEnrollmentResult {
        eventBus.publish(MfaEvent.EnrollmentStarted(
            eventId = UUID.randomUUID(),
            timestamp = CurrentKotlinInstant,
            realmId = realmId,
            userId = userId,
            methodType = MfaMethodType.TOTP,
            contactValue = accountName
        ))

        val secret = totpGenerator.generateSecret()
        val qrCodeUri = totpGenerator.generateQrCodeUri(
            secret = secret,
            issuer = config.totpIssuer,
            accountName = accountName
        )
        val qrCodeDataUri = qrCodeGenerator.generateDataUri(qrCodeUri)

        val encrypted = secretEncryption.encrypt(secret)

        val methodId = kodexTransaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            MfaMethods.insertAndGetId {
                it[MfaMethods.realmId] = this@DefaultMfaService.realmId
                it[MfaMethods.userId] = userId
                it[methodType] = MfaMethodType.TOTP
                it[identifier] = "TOTP"
                it[encryptedSecret] = encrypted.ciphertext
                it[encryptionNonce] = encrypted.nonce
                it[isActive] = false
                it[isPrimary] = false
                it[enrolledAt] = now
            }.value
        }

        return TotpEnrollmentResult(
            methodId = methodId,
            secret = secret,
            qrCodeDataUri = qrCodeDataUri,
            issuer = config.totpIssuer,
            accountName = accountName
        )
    }

    override suspend fun verifyTotpEnrollment(
        userId: UUID,
        methodId: UUID,
        code: String
    ): EnrollmentVerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            val method = kodexTransaction {
                MfaMethods
                    .selectAll()
                    .where {
                        (MfaMethods.realmId eq realmId) and
                        (MfaMethods.userId eq userId) and
                        (MfaMethods.id eq methodId) and
                        (MfaMethods.methodType eq MfaMethodType.TOTP) and
                        (MfaMethods.isActive eq false)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No pending TOTP enrollment for this method")

            val encryptedSecret = method[MfaMethods.encryptedSecret]
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No secret found")
            val nonce = method[MfaMethods.encryptionNonce]
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No nonce found")

            val secret = secretEncryption.decrypt(EncryptedSecret(encryptedSecret, nonce))

            val isValid = totpValidator.validate(secret, code)

            if (!isValid) {
                eventBus.publish(MfaEvent.EnrollmentFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodType = MfaMethodType.TOTP,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS
                ))
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            kodexTransaction {
                val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

                MfaMethods.update({
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.id eq methodId)
                }) {
                    it[isActive] = true
                    it[isPrimary] = !hasAnyMethod(userId)
                    it[lastUsedAt] = now
                }
            }

            val backupCodes = generateBackupCodes(userId)

            eventBus.publish(MfaEvent.EnrollmentCompleted(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = MfaMethodType.TOTP,
                isPrimary = !hasAnyMethod(userId)
            ))

            EnrollmentVerificationResult.Success(backupCodes)
        }
    }

    override suspend fun challengeEmail(
        userId: UUID,
        methodId: UUID,
        ipAddress: String?
    ): ChallengeResult {
        // Retrieve the MFA method to get the email address
        val method = kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.id eq methodId) and
                    (MfaMethods.isActive eq true)
                }
                .singleOrNull()
        } ?: return ChallengeResult.Failed("MFA method not found or inactive")

        if (method[MfaMethods.methodType] != MfaMethodType.EMAIL) {
            return ChallengeResult.Failed("Method is not an email MFA method")
        }

        val email = method[MfaMethods.identifier]
            ?: return ChallengeResult.Failed("Email address not found for this MFA method")

        val userKey = "mfa:challenge:user:$userId"
        val emailKey = "mfa:challenge:email:$email"
        val ipKey = ipAddress?.let { "mfa:challenge:ip:$it" }

        val userReservation = rateLimiter.checkAndReserve(
            userKey,
            config.maxChallengeAttemptsPerUser,
            config.challengeRateLimitWindow,
            config.challengeCooldownPeriod
        )

        if (!userReservation.isAllowed()) {
            val result = when (val rateLimitResult = userReservation.result) {
                is RateLimitResult.Exceeded -> ChallengeResult.RateLimitExceeded(rateLimitResult.reason, null)
                is RateLimitResult.Cooldown -> ChallengeResult.Cooldown(rateLimitResult.reason, rateLimitResult.retryAfter!!)
                else -> ChallengeResult.Failed("Rate limit check failed")
            }
            eventBus.publish(MfaEvent.RateLimitExceeded(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                operation = "challenge",
                reason = when (val r = userReservation.result) {
                    is RateLimitResult.Exceeded -> r.reason
                    is RateLimitResult.Cooldown -> r.reason
                    else -> "Rate limit check failed"
                },
                retryAfter = (userReservation.result as? RateLimitResult.Cooldown)?.retryAfter,
                sourceIp = ipAddress
            ))
            return result
        }

        val emailReservation = rateLimiter.checkAndReserve(
            emailKey,
            config.maxChallengeAttemptsPerContact,
            config.challengeRateLimitWindow
        )

        if (!emailReservation.isAllowed()) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            val result = when (val rateLimitResult = emailReservation.result) {
                is RateLimitResult.Exceeded -> ChallengeResult.RateLimitExceeded(rateLimitResult.reason, null)
                else -> ChallengeResult.Failed("Rate limit check failed")
            }
            eventBus.publish(MfaEvent.RateLimitExceeded(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                operation = "challenge",
                reason = when (val r = emailReservation.result) {
                    is RateLimitResult.Exceeded -> r.reason
                    else -> "Rate limit check failed"
                },
                retryAfter = null,
                sourceIp = ipAddress
            ))
            return result
        }

        val ipReservation = ipKey?.let {
            rateLimiter.checkAndReserve(
                it,
                config.maxChallengeAttemptsPerIp,
                config.challengeRateLimitWindow
            )
        }

        if (ipReservation != null && !ipReservation.isAllowed()) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(emailReservation.reservationId)
            val result = when (val rateLimitResult = ipReservation.result) {
                is RateLimitResult.Exceeded -> ChallengeResult.RateLimitExceeded(rateLimitResult.reason, null)
                else -> ChallengeResult.Failed("Rate limit check failed")
            }
            eventBus.publish(MfaEvent.RateLimitExceeded(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                operation = "challenge",
                reason = when (val r = ipReservation.result) {
                    is RateLimitResult.Exceeded -> r.reason
                    else -> "Rate limit check failed"
                },
                retryAfter = null,
                sourceIp = ipAddress
            ))
            return result
        }

        val code = TokenGenerator.generate(NumericFormat(config.codeLength))

        try {
            config.emailSender?.send(email, code)
        } catch (e: Exception) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(emailReservation.reservationId)
            ipReservation?.let { rateLimiter.releaseReservation(it.reservationId) }
            return ChallengeResult.Failed("Failed to send code: ${e.message}")
        }

        val challengeId = kodexTransaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, CurrentKotlinInstant)
            val codeHash = hashingService.hash(code)

            MfaChallenges.insert {
                it[MfaChallenges.realmId] = this@DefaultMfaService.realmId
                it[MfaChallenges.userId] = userId
                it[MfaChallenges.methodId] = methodId
                it[MfaChallenges.codeHash] = codeHash
                it[MfaChallenges.expiresAt] = expiresAt
                it[MfaChallenges.createdAt] = now
                it[MfaChallenges.attempts] = 0
                it[MfaChallenges.maxAttempts] = config.maxVerifyAttempts
            }[MfaChallenges.id].value
        }

        eventBus.publish(MfaEvent.ChallengeSent(
            eventId = UUID.randomUUID(),
            timestamp = CurrentKotlinInstant,
            realmId = realmId,
            userId = userId,
            methodId = methodId,
            challengeId = challengeId,
            methodType = MfaMethodType.EMAIL,
            sourceIp = ipAddress
        ))

        return ChallengeResult.Success(challengeId)
    }

    override suspend fun verifyChallenge(
        userId: UUID,
        challengeId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            val challenge = kodexTransaction {
                MfaChallenges
                    .selectAll()
                    .where {
                        (MfaChallenges.realmId eq realmId) and
                        (MfaChallenges.id eq challengeId) and
                        (MfaChallenges.userId eq userId)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime VerificationResult.Invalid("Invalid challenge")

            val methodId = challenge[MfaChallenges.methodId]
            val attempts = challenge[MfaChallenges.attempts]
            val maxAttempts = challenge[MfaChallenges.maxAttempts]
            val expiresAt = challenge[MfaChallenges.expiresAt]
            val verifiedAt = challenge[MfaChallenges.verifiedAt]
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            if (verifiedAt != null) {
                return@ensureMinimumResponseTime VerificationResult.Invalid("Challenge already verified")
            }

            if (now > expiresAt) {
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodId = methodId,
                    challengeId = challengeId,
                    methodType = MfaMethodType.EMAIL,
                    reason = "Code has expired",
                    failureReason = FailureReason.TOKEN_EXPIRED,
                    attemptsRemaining = null,
                    sourceIp = ipAddress
                ))
                return@ensureMinimumResponseTime VerificationResult.Expired("Code has expired")
            }

            if (attempts >= maxAttempts) {
                eventBus.publish(MfaEvent.RateLimitExceeded(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodType = MfaMethodType.EMAIL,
                    operation = "verification",
                    reason = "Too many attempts",
                    retryAfter = null,
                    sourceIp = ipAddress
                ))
                return@ensureMinimumResponseTime VerificationResult.RateLimitExceeded("Too many attempts")
            }

            val codeHash = challenge[MfaChallenges.codeHash]
            val isValid = hashingService.verify(code, codeHash)

            if (!isValid) {
                kodexTransaction {
                    MfaChallenges.update({
                        (MfaChallenges.realmId eq realmId) and (MfaChallenges.id eq challengeId)
                    }) {
                        it[MfaChallenges.attempts] = attempts + 1
                    }
                }
                val remainingAttempts = maxAttempts - (attempts + 1)
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodId = methodId,
                    challengeId = challengeId,
                    methodType = MfaMethodType.EMAIL,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS,
                    attemptsRemaining = remainingAttempts,
                    sourceIp = ipAddress
                ))
                return@ensureMinimumResponseTime VerificationResult.Invalid("Invalid code")
            }

            kodexTransaction {
                MfaChallenges.update({
                    (MfaChallenges.realmId eq realmId) and (MfaChallenges.id eq challengeId)
                }) {
                    it[MfaChallenges.verifiedAt] = now
                }

                MfaMethods.update({
                    (MfaMethods.realmId eq realmId) and (MfaMethods.id eq methodId)
                }) {
                    it[MfaMethods.lastUsedAt] = CurrentKotlinInstant.toLocalDateTime(timeZone)
                }
            }

            eventBus.publish(MfaEvent.VerificationSuccess(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                challengeId = challengeId,
                methodType = MfaMethodType.EMAIL,
                sourceIp = ipAddress
            ))

            VerificationResult.Success
        }
    }

    override suspend fun verifyTotp(
        userId: UUID,
        methodId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            val codeHash = hashingService.hash(code)

            val method = kodexTransaction {
                MfaMethods
                    .selectAll()
                    .where {
                        (MfaMethods.realmId eq realmId) and
                        (MfaMethods.userId eq userId) and
                        (MfaMethods.id eq methodId) and
                        (MfaMethods.methodType eq MfaMethodType.TOTP) and
                        (MfaMethods.isActive eq true)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime VerificationResult.Invalid("TOTP method not found or inactive")

            val encryptedSecret = method[MfaMethods.encryptedSecret]
                ?: return@ensureMinimumResponseTime VerificationResult.Invalid("No secret found")
            val nonce = method[MfaMethods.encryptionNonce]
                ?: return@ensureMinimumResponseTime VerificationResult.Invalid("No nonce found")

            val secret = secretEncryption.decrypt(EncryptedSecret(encryptedSecret, nonce))

            // Check if code has been used recently (replay attack protection)
            val codeAlreadyUsed = kodexTransaction {
                MfaTotpUsedCodes
                    .selectAll()
                    .where {
                        (MfaTotpUsedCodes.realmId eq realmId) and
                        (MfaTotpUsedCodes.userId eq userId) and
                        (MfaTotpUsedCodes.methodId eq methodId) and
                        (MfaTotpUsedCodes.codeHash eq codeHash)
                    }
                    .count() > 0
            }

            if (codeAlreadyUsed) {
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodId = methodId,
                    challengeId = null,
                    methodType = MfaMethodType.TOTP,
                    reason = "Code already used (replay attack detected)",
                    failureReason = FailureReason.INVALID_CREDENTIALS,
                    attemptsRemaining = null,
                    sourceIp = ipAddress
                ))
                return@ensureMinimumResponseTime VerificationResult.Invalid("Code already used")
            }

            val isValid = totpValidator.validate(secret, code)

            if (!isValid) {
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realmId,
                    userId = userId,
                    methodId = methodId,
                    challengeId = null,
                    methodType = MfaMethodType.TOTP,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS,
                    attemptsRemaining = null,
                    sourceIp = ipAddress
                ))
                return@ensureMinimumResponseTime VerificationResult.Invalid("Invalid code")
            }

            kodexTransaction {
                val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

                // Record the used code
                MfaTotpUsedCodes.insert {
                    it[MfaTotpUsedCodes.realmId] = this@DefaultMfaService.realmId
                    it[MfaTotpUsedCodes.userId] = userId
                    it[MfaTotpUsedCodes.methodId] = methodId
                    it[MfaTotpUsedCodes.codeHash] = codeHash
                    it[MfaTotpUsedCodes.usedAt] = now
                }

                // Update last used timestamp
                MfaMethods.update({
                    (MfaMethods.realmId eq realmId) and (MfaMethods.id eq methodId)
                }) {
                    it[MfaMethods.lastUsedAt] = now
                }

                // Clean up old used codes (keep only last 3 time windows = 90 seconds with 30s period)
                val cutoffTime = CurrentKotlinInstant.minus(90.seconds).toLocalDateTime(timeZone)
                MfaTotpUsedCodes.deleteWhere {
                    (MfaTotpUsedCodes.realmId eq this@DefaultMfaService.realmId) and
                    (MfaTotpUsedCodes.usedAt less cutoffTime)
                }
            }

            eventBus.publish(MfaEvent.VerificationSuccess(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                challengeId = null,
                methodType = MfaMethodType.TOTP,
                sourceIp = ipAddress
            ))

            VerificationResult.Success
        }
    }

    override fun getMethods(userId: UUID): List<MfaMethodInfo> {
        return kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.isActive eq true)
                }
                .map {
                    MfaMethodInfo(
                        id = it[MfaMethods.id].value,
                        type = it[MfaMethods.methodType],
                        identifier = it[MfaMethods.identifier],
                        isPrimary = it[MfaMethods.isPrimary],
                        lastUsedAt = it[MfaMethods.lastUsedAt]?.toInstant(timeZone)
                    )
                }
        }
    }

    override suspend fun removeMethod(userId: UUID, methodId: UUID) {
        val methodInfo = kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.id eq methodId)
                }
                .singleOrNull()
                ?.let {
                    it[MfaMethods.methodType]
                }
        }

        kodexTransaction {
            MfaMethods.deleteWhere {
                (MfaMethods.realmId eq realmId) and
                (MfaMethods.userId eq userId) and
                (MfaMethods.id eq methodId)
            }
        }

        if (methodInfo != null) {
            eventBus.publish(MfaEvent.MethodRemoved(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = methodInfo,
                actorId = userId
            ))
        }
    }

    override suspend fun setPrimaryMethod(userId: UUID, methodId: UUID) {
        val oldPrimaryId = kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.isPrimary eq true)
                }
                .singleOrNull()
                ?.let { it[MfaMethods.id].value }
        }

        val newMethodType = kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.id eq methodId)
                }
                .singleOrNull()
                ?.let { it[MfaMethods.methodType] }
        }

        kodexTransaction {
            MfaMethods.update({
                (MfaMethods.realmId eq realmId) and (MfaMethods.userId eq userId)
            }) {
                it[isPrimary] = false
            }

            MfaMethods.update({
                (MfaMethods.realmId eq realmId) and (MfaMethods.userId eq userId) and (MfaMethods.id eq methodId)
            }) {
                it[isPrimary] = true
            }
        }

        if (newMethodType != null) {
            eventBus.publish(MfaEvent.PrimaryMethodChanged(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                oldPrimaryMethodId = oldPrimaryId,
                newPrimaryMethodId = methodId,
                methodType = newMethodType,
                actorId = userId
            ))
        }
    }

    override suspend fun generateBackupCodes(userId: UUID): List<String> {
        // Verify user has at least one active MFA method
        if (!hasAnyMethod(userId)) {
            throw KodexThrowable.Authorization.InsufficientPermissions(
                requiredRole = "MFA_ENROLLED",
                userId = userId
            )
        }

        val codes = mutableListOf<String>()

        kodexTransaction {
            MfaBackupCodes.deleteWhere {
                (MfaBackupCodes.realmId eq realmId) and (MfaBackupCodes.userId eq userId)
            }

            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            repeat(config.backupCodesCount) {
                val code = TokenGenerator.generate(AlphanumericFormat(config.backupCodeLength, true))
                val codeHash = hashingService.hash(code)

                MfaBackupCodes.insert {
                    it[MfaBackupCodes.realmId] = this@DefaultMfaService.realmId
                    it[MfaBackupCodes.userId] = userId
                    it[MfaBackupCodes.codeHash] = codeHash
                    it[usedAt] = null
                    it[createdAt] = now
                }

                codes.add(code)
            }
        }

        eventBus.publish(MfaEvent.BackupCodesGenerated(
            eventId = UUID.randomUUID(),
            timestamp = CurrentKotlinInstant,
            realmId = realmId,
            userId = userId,
            codeCount = config.backupCodesCount,
            actorId = userId
        ))

        return codes
    }

    override suspend fun verifyBackupCode(
        userId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            // Check rate limits for backup code verification
            val userLimit = rateLimiter.checkLimit(
                key = "mfa:backup_code_verify:user:$userId",
                limit = config.maxBackupCodeAttemptsPerUser,
                window = config.backupCodeRateLimitWindow
            )

            if (userLimit !is RateLimitResult.Allowed) {
                val reason = when (userLimit) {
                    is RateLimitResult.Exceeded -> userLimit.reason
                    is RateLimitResult.Cooldown -> userLimit.reason
                    else -> "Rate limit exceeded"
                }
                return@ensureMinimumResponseTime VerificationResult.RateLimitExceeded(reason)
            }

            val ipLimit = ipAddress?.let { ip ->
                rateLimiter.checkLimit(
                    key = "mfa:backup_code_verify:ip:$ip",
                    limit = config.maxBackupCodeAttemptsPerIp,
                    window = config.backupCodeRateLimitWindow
                )
            }

            if (ipLimit != null && ipLimit !is RateLimitResult.Allowed) {
                val reason = when (ipLimit) {
                    is RateLimitResult.Exceeded -> ipLimit.reason
                    is RateLimitResult.Cooldown -> ipLimit.reason
                    else -> "Rate limit exceeded for IP address"
                }
                return@ensureMinimumResponseTime VerificationResult.RateLimitExceeded(reason)
            }

            val backupCodes = kodexTransaction {
                MfaBackupCodes
                    .selectAll()
                    .where {
                        (MfaBackupCodes.realmId eq realmId) and
                        (MfaBackupCodes.userId eq userId) and
                        (MfaBackupCodes.usedAt.isNull())
                    }
                    .toList()
            }

            for (backupCode in backupCodes) {
                val codeHash = backupCode[MfaBackupCodes.codeHash]
                if (hashingService.verify(code, codeHash)) {
                    val codeId = backupCode[MfaBackupCodes.id].value
                    kodexTransaction {
                        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)
                        MfaBackupCodes.update({
                            (MfaBackupCodes.realmId eq realmId) and (MfaBackupCodes.id eq codeId)
                        }) {
                            it[usedAt] = now
                        }
                    }

                    val remainingCodes = kodexTransaction {
                        MfaBackupCodes
                            .selectAll()
                            .where {
                                (MfaBackupCodes.realmId eq realmId) and
                                (MfaBackupCodes.userId eq userId) and
                                (MfaBackupCodes.usedAt.isNull())
                            }
                            .count()
                            .toInt()
                    }

                    eventBus.publish(MfaEvent.BackupCodeUsed(
                        eventId = UUID.randomUUID(),
                        timestamp = CurrentKotlinInstant,
                        realmId = realmId,
                        userId = userId,
                        codeId = codeId,
                        codesRemaining = remainingCodes,
                        sourceIp = ipAddress
                    ))

                    return@ensureMinimumResponseTime VerificationResult.Success
                }
            }

            VerificationResult.Invalid("Invalid backup code")
        }
    }

    override suspend fun verifyMfaSession(
        sessionId: String,
        code: String,
        methodId: UUID?
    ): VerificationResult {
        // Get MFA session
        val session = sessionStore.getSession(sessionId)
            ?: return VerificationResult.Invalid("Invalid or expired MFA session")

        // Determine verification method and verify code
        val result = when {
            // TOTP verification (requires methodId)
            methodId != null -> verifyTotp(session.userId, methodId, code, session.ipAddress)

            // Backup code verification (code length matches backup code length)
            code.length == config.backupCodeLength ->
                verifyBackupCode(session.userId, code, session.ipAddress)

            // Invalid - no method ID provided and code is not a backup code
            else -> VerificationResult.Invalid("Invalid verification method or code")
        }

        // If verification successful, mark session as verified and auto-trust device
        if (result is VerificationResult.Success) {
            sessionStore.markAsVerified(sessionId)

            // Auto-trust device if enabled
            if (config.autoTrustDeviceAfterVerification &&
                session.ipAddress != null &&
                session.userAgent != null) {

                val expiresInDays = config.defaultTrustedDeviceExpiry?.inWholeDays?.toInt()
                trustDevice(
                    userId = session.userId,
                    ipAddress = session.ipAddress,
                    userAgent = session.userAgent,
                    deviceName = null,
                    expiresInDays = expiresInDays
                )
            }
        }

        return result
    }

    override fun hasAnyMethod(userId: UUID): Boolean {
        return kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.isActive eq true)
                }
                .count() > 0
        }
    }

    override fun isMfaRequired(userId: UUID): Boolean {
        return config.requireMfa && hasAnyMethod(userId)
    }

    // Trusted Devices
    override suspend fun trustDevice(
        userId: UUID,
        ipAddress: String?,
        userAgent: String?,
        deviceName: String?,
        expiresInDays: Int?
    ): UUID {
        // Generate device fingerprint from IP and user agent
        val deviceFingerprint = DeviceFingerprint.generate(ipAddress, userAgent)

        val now = CurrentKotlinInstant
        val deviceId = kodexTransaction {
            val nowLocal = now.toLocalDateTime(timeZone)

            val effectiveExpiresInDays = expiresInDays
                ?: config.defaultTrustedDeviceExpiry?.inWholeDays?.toInt()

            val expiresAt = effectiveExpiresInDays?.let {
                now.plus(it.days).toLocalDateTime(timeZone)
            }

            MfaTrustedDevices.insert {
                it[MfaTrustedDevices.realmId] = this@DefaultMfaService.realmId
                it[MfaTrustedDevices.userId] = userId
                it[MfaTrustedDevices.deviceFingerprint] = deviceFingerprint
                it[MfaTrustedDevices.deviceName] = deviceName
                it[MfaTrustedDevices.ipAddress] = ipAddress
                it[MfaTrustedDevices.userAgent] = userAgent
                it[MfaTrustedDevices.trustedAt] = nowLocal
                it[MfaTrustedDevices.lastUsedAt] = null
                it[MfaTrustedDevices.expiresAt] = expiresAt
            }[MfaTrustedDevices.id].value
        }

        eventBus.publish(MfaEvent.DeviceTrusted(
            eventId = UUID.randomUUID(),
            timestamp = now,
            realmId = realmId,
            userId = userId,
            deviceId = deviceId,
            deviceFingerprint = deviceFingerprint,
            deviceName = deviceName,
            expiresAt = expiresInDays?.let { now.plus(it.days) }
                ?: config.defaultTrustedDeviceExpiry?.let { now.plus(it) },
            sourceIp = ipAddress,
            userAgent = userAgent
        ))

        return deviceId
    }

    override suspend fun isDeviceTrusted(
        userId: UUID,
        ipAddress: String?,
        userAgent: String?
    ): Boolean {
        // Generate device fingerprint from IP and user agent
        val deviceFingerprint = DeviceFingerprint.generate(ipAddress, userAgent)

        return kodexTransaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            val device = MfaTrustedDevices
                .selectAll()
                .where {
                    (MfaTrustedDevices.realmId eq realmId) and
                    (MfaTrustedDevices.userId eq userId) and
                    (MfaTrustedDevices.deviceFingerprint eq deviceFingerprint)
                }
                .singleOrNull()

            if (device == null) {
                return@kodexTransaction false
            }

            val expiresAt = device[MfaTrustedDevices.expiresAt]
            if (expiresAt != null && now > expiresAt) {
                return@kodexTransaction false
            }

            // Update last used timestamp
            MfaTrustedDevices.update({
                (MfaTrustedDevices.realmId eq realmId) and
                (MfaTrustedDevices.userId eq userId) and
                (MfaTrustedDevices.deviceFingerprint eq deviceFingerprint)
            }) {
                it[lastUsedAt] = now
            }

            true
        }
    }

    override suspend fun getTrustedDevices(userId: UUID): List<TrustedDeviceInfo> {
        return kodexTransaction {
            MfaTrustedDevices
                .selectAll()
                .where {
                    (MfaTrustedDevices.realmId eq realmId) and
                    (MfaTrustedDevices.userId eq userId)
                }
                .map {
                    TrustedDeviceInfo(
                        id = it[MfaTrustedDevices.id].value,
                        deviceFingerprint = it[MfaTrustedDevices.deviceFingerprint],
                        deviceName = it[MfaTrustedDevices.deviceName],
                        ipAddress = it[MfaTrustedDevices.ipAddress],
                        userAgent = it[MfaTrustedDevices.userAgent],
                        trustedAt = it[MfaTrustedDevices.trustedAt].toInstant(timeZone),
                        lastUsedAt = it[MfaTrustedDevices.lastUsedAt]?.toInstant(timeZone),
                        expiresAt = it[MfaTrustedDevices.expiresAt]?.toInstant(timeZone)
                    )
                }
        }
    }

    override suspend fun removeTrustedDevice(userId: UUID, deviceId: UUID) {
        val deviceInfo = kodexTransaction {
            MfaTrustedDevices
                .selectAll()
                .where {
                    (MfaTrustedDevices.realmId eq realmId) and
                    (MfaTrustedDevices.userId eq userId) and
                    (MfaTrustedDevices.id eq deviceId)
                }
                .singleOrNull()
                ?.let { it[MfaTrustedDevices.deviceFingerprint] }
        }

        kodexTransaction {
            MfaTrustedDevices.deleteWhere {
                (MfaTrustedDevices.realmId eq realmId) and
                (MfaTrustedDevices.userId eq userId) and
                (MfaTrustedDevices.id eq deviceId)
            }
        }

        if (deviceInfo != null) {
            eventBus.publish(MfaEvent.DeviceUntrusted(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                deviceId = deviceId,
                deviceFingerprint = deviceInfo,
                actorId = userId
            ))
        }
    }

    override suspend fun removeAllTrustedDevices(userId: UUID) {
        kodexTransaction {
            MfaTrustedDevices.deleteWhere {
                (MfaTrustedDevices.realmId eq realmId) and (MfaTrustedDevices.userId eq userId)
            }
        }
    }

    // Admin Management
    override suspend fun forceRemoveMfaMethod(adminId: UUID, userId: UUID, methodId: UUID) {
        requireAdminRole(adminId)

        val methodInfo = kodexTransaction {
            MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.id eq methodId)
                }
                .singleOrNull()
                ?.let {
                    it[MfaMethods.methodType]
                }
        }

        kodexTransaction {
            MfaMethods.deleteWhere {
                (MfaMethods.realmId eq realmId) and
                (MfaMethods.userId eq userId) and
                (MfaMethods.id eq methodId)
            }
        }

        if (methodInfo != null) {
            eventBus.publish(MfaEvent.MethodRemoved(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = methodInfo,
                actorId = adminId
            ))
        }
    }

    override suspend fun disableMfaForUser(adminId: UUID, userId: UUID) {
        requireAdminRole(adminId)

        kodexTransaction {
            MfaMethods.deleteWhere {
                (MfaMethods.realmId eq realmId) and (MfaMethods.userId eq userId)
            }
            MfaBackupCodes.deleteWhere {
                (MfaBackupCodes.realmId eq realmId) and (MfaBackupCodes.userId eq userId)
            }
            MfaTrustedDevices.deleteWhere {
                (MfaTrustedDevices.realmId eq realmId) and (MfaTrustedDevices.userId eq userId)
            }
        }
    }

    override suspend fun listUserMethods(adminId: UUID, userId: UUID): List<MfaMethodInfo> {
        requireAdminRole(adminId)

        return getMethods(userId)
    }

    // Statistics
    override suspend fun getMfaStatistics(): MfaStatistics {
        val totalUsers = config.getTotalUsers?.invoke() ?: 0L
        return kodexTransaction {

            val usersWithMfa = MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.isActive eq true)
                }
                .map { it[MfaMethods.userId] }
                .distinct()
                .count()
                .toLong()

            val adoptionRate = if (totalUsers > 0) {
                (usersWithMfa.toDouble() / totalUsers.toDouble()) * 100.0
            } else 0.0

            val methodDistribution = MfaMethods
                .selectAll()
                .where {
                    (MfaMethods.realmId eq realmId) and
                    (MfaMethods.isActive eq true)
                }
                .groupBy { it[MfaMethods.methodType] }
                .mapValues { it.value.size.toLong() }

            val trustedDevices = MfaTrustedDevices
                .selectAll()
                .where { MfaTrustedDevices.realmId eq realmId }
                .count()

            MfaStatistics(
                totalUsers = totalUsers,
                usersWithMfa = usersWithMfa,
                adoptionRate = adoptionRate,
                methodDistribution = methodDistribution,
                trustedDevices = trustedDevices
            )
        }
    }

    private suspend fun <T> ensureMinimumResponseTime(
        minimumTime: Duration,
        block: suspend () -> T
    ): T {
        val startTime = CurrentKotlinInstant
        val result = block()
        val elapsed = CurrentKotlinInstant - startTime
        val remaining = minimumTime - elapsed

        if (remaining.isPositive()) {
            delay(remaining.inWholeMilliseconds)
        }

        return result
    }
}
