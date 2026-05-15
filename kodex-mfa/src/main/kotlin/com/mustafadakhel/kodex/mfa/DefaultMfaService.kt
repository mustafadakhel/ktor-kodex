package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.FailureReason
import com.mustafadakhel.kodex.mfa.device.DeviceFingerprint
import com.mustafadakhel.kodex.mfa.encryption.EncryptedSecret
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.event.MfaEvent
import com.mustafadakhel.kodex.mfa.schema.MfaSchema
import com.mustafadakhel.kodex.mfa.totp.QrCodeGenerator
import com.mustafadakhel.kodex.mfa.totp.TotpGenerator
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.mfa.totp.TotpValidator
import com.mustafadakhel.kodex.ratelimit.RateLimitResult
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.tokens.token.AlphanumericFormat
import com.mustafadakhel.kodex.tokens.token.NumericFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class DefaultMfaService(
    private val db: KodexDatabase,
    private val schema: MfaSchema,
    private val config: MfaConfig,
    private val timeZone: TimeZone,
    private val hashingService: HashingService,
    private val secretEncryption: SecretEncryption,
    private val eventBus: EventBus,
    private val realmId: String,
    private val rateLimiter: RateLimiter,
    private val sessionStore: MfaSessionStore
) : MfaService {

    private val totpGenerator = TotpGenerator(
        algorithm = config.totpAlgorithm,
        digits = config.totpDigits,
        period = config.totpPeriod
    )
    private val totpValidator = TotpValidator(totpGenerator, config.totpTimeStepWindow)
    private val qrCodeGenerator = QrCodeGenerator()

    private val methods = schema.mfaMethods
    private val challenges = schema.mfaChallenges
    private val backupCodes = schema.mfaBackupCodes
    private val totpUsedCodes = schema.mfaTotpUsedCodes
    private val trustedDevices = schema.mfaTrustedDevices

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
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)

        eventBus.publish(MfaEvent.EnrollmentStarted(
            eventId = UUID.randomUUID(),
            timestamp = now,
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
                timestamp = now,
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
                timestamp = now,
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
                timestamp = now,
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
                timestamp = now,
                realmId = realmId,
                userId = userId,
                methodType = MfaMethodType.EMAIL,
                reason = "Failed to send code: ${e.message}",
                failureReason = FailureReason.UNKNOWN,
                sourceIp = ipAddress
            ))
            return EnrollmentResult.Failed("Failed to send code: ${e.message}")
        }

        val challengeId = db.transaction {
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, now)
            val codeHash = hashingService.hash(code)

            val methodId = methods.insert {
                it[methods.realmId] = this@DefaultMfaService.realmId
                it[methods.userId] = userId
                it[methods.methodType] = MfaMethodType.EMAIL
                it[methods.identifier] = email
                it[methods.encryptedSecret] = null
                it[methods.encryptionNonce] = null
                it[methods.isActive] = false
                it[methods.isPrimary] = false
                it[methods.enrolledAt] = nowLocal
            }[methods.id]

            challenges.insert {
                it[challenges.realmId] = this@DefaultMfaService.realmId
                it[challenges.userId] = userId
                it[challenges.methodId] = methodId
                it[challenges.codeHash] = codeHash
                it[challenges.expiresAt] = expiresAt
                it[challenges.createdAt] = nowLocal
                it[challenges.attempts] = 0
                it[challenges.maxAttempts] = config.maxVerifyAttempts
            }[challenges.id]
        }

        return EnrollmentResult.CodeSent(challengeId)
    }

    override suspend fun verifyEmailEnrollment(
        userId: UUID,
        challengeId: UUID,
        code: String
    ): EnrollmentVerificationResult {
        return ensureMinimumResponseTime(250.milliseconds) {
            val now = CurrentKotlinInstant
            val nowLocal = now.toLocalDateTime(timeZone)

            val challenge = db.transaction {
                challenges
                    .selectAll()
                    .where { (challenges.realmId eq realmId) and (challenges.id eq challengeId) }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid challenge")

            val userId = challenge[challenges.userId]
            val attempts = challenge[challenges.attempts]
            val maxAttempts = challenge[challenges.maxAttempts]
            val expiresAt = challenge[challenges.expiresAt]
            val verifiedAt = challenge[challenges.verifiedAt]

            if (verifiedAt != null) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Challenge already verified")
            }

            if (nowLocal > expiresAt) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Expired("Code has expired")
            }

            if (attempts >= maxAttempts) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.RateLimitExceeded("Too many attempts")
            }

            val codeHash = challenge[challenges.codeHash]
            val isValid = hashingService.verify(code, codeHash)

            if (!isValid) {
                db.transaction {
                    challenges.update({
                        (challenges.realmId eq realmId) and (challenges.id eq challengeId)
                    }) {
                        it[challenges.attempts] = attempts + 1
                    }
                }
                eventBus.publish(MfaEvent.EnrollmentFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realmId,
                    userId = userId,
                    methodType = MfaMethodType.EMAIL,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS
                ))
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            val methodId = db.transaction {
                challenges.update({
                    (challenges.realmId eq realmId) and (challenges.id eq challengeId)
                }) {
                    it[challenges.verifiedAt] = nowLocal
                }

                val methodId = challenge[challenges.methodId]
                val hadAnyMethod = hasAnyMethod(userId)
                methods.update({
                    (methods.realmId eq realmId) and (methods.id eq methodId)
                }) {
                    it[methods.isActive] = true
                    it[methods.isPrimary] = !hadAnyMethod
                }

                methodId
            }

            val backupCodesList = generateBackupCodes(userId)

            eventBus.publish(MfaEvent.EnrollmentCompleted(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = MfaMethodType.EMAIL,
                isPrimary = !hasAnyMethod(userId)
            ))

            EnrollmentVerificationResult.Success(backupCodesList)
        }
    }

    override suspend fun enrollTotp(userId: UUID, accountName: String): TotpEnrollmentResult {
        val now = CurrentKotlinInstant

        eventBus.publish(MfaEvent.EnrollmentStarted(
            eventId = UUID.randomUUID(),
            timestamp = now,
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

        val methodId = db.transaction {
            val nowLocal = now.toLocalDateTime(timeZone)

            methods.insert {
                it[methods.realmId] = this@DefaultMfaService.realmId
                it[methods.userId] = userId
                it[methods.methodType] = MfaMethodType.TOTP
                it[methods.identifier] = "TOTP"
                it[methods.encryptedSecret] = encrypted.ciphertext
                it[methods.encryptionNonce] = encrypted.nonce
                it[methods.isActive] = false
                it[methods.isPrimary] = false
                it[methods.enrolledAt] = nowLocal
            }[methods.id]
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
        return ensureMinimumResponseTime(250.milliseconds) {
            val now = CurrentKotlinInstant
            val nowLocal = now.toLocalDateTime(timeZone)

            val method = db.transaction {
                methods
                    .selectAll()
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.id eq methodId) and
                        (methods.methodType eq MfaMethodType.TOTP) and
                        (methods.isActive eq false)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No pending TOTP enrollment for this method")

            val encryptedSecret = method[methods.encryptedSecret]
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No secret found")
            val nonce = method[methods.encryptionNonce]
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No nonce found")

            val secret = secretEncryption.decrypt(EncryptedSecret(encryptedSecret, nonce))

            val isValid = totpValidator.validate(secret, code)

            if (!isValid) {
                eventBus.publish(MfaEvent.EnrollmentFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realmId,
                    userId = userId,
                    methodType = MfaMethodType.TOTP,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS
                ))
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            db.transaction {
                methods.update({
                    (methods.realmId eq realmId) and
                    (methods.id eq methodId)
                }) {
                    it[methods.isActive] = true
                    it[methods.isPrimary] = !hasAnyMethod(userId)
                    it[methods.lastUsedAt] = nowLocal
                }
            }

            val backupCodesList = generateBackupCodes(userId)

            eventBus.publish(MfaEvent.EnrollmentCompleted(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = MfaMethodType.TOTP,
                isPrimary = !hasAnyMethod(userId)
            ))

            EnrollmentVerificationResult.Success(backupCodesList)
        }
    }

    override suspend fun challengeEmail(
        userId: UUID,
        methodId: UUID,
        ipAddress: String?
    ): ChallengeResult {
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)

        val method = db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId) and
                    (methods.isActive eq true)
                }
                .singleOrNull()
        } ?: return ChallengeResult.Failed("MFA method not found or inactive")

        if (method[methods.methodType] != MfaMethodType.EMAIL) {
            return ChallengeResult.Failed("Method is not an email MFA method")
        }

        val email = method[methods.identifier]
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
                timestamp = now,
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
                timestamp = now,
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
                timestamp = now,
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

        val challengeId = db.transaction {
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, now)
            val codeHash = hashingService.hash(code)

            challenges.insert {
                it[challenges.realmId] = this@DefaultMfaService.realmId
                it[challenges.userId] = userId
                it[challenges.methodId] = methodId
                it[challenges.codeHash] = codeHash
                it[challenges.expiresAt] = expiresAt
                it[challenges.createdAt] = nowLocal
                it[challenges.attempts] = 0
                it[challenges.maxAttempts] = config.maxVerifyAttempts
            }[challenges.id]
        }

        eventBus.publish(MfaEvent.ChallengeSent(
            eventId = UUID.randomUUID(),
            timestamp = now,
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
        return ensureMinimumResponseTime(250.milliseconds) {
            val now = CurrentKotlinInstant
            val nowLocal = now.toLocalDateTime(timeZone)

            val challenge = db.transaction {
                challenges
                    .selectAll()
                    .where {
                        (challenges.realmId eq realmId) and
                        (challenges.id eq challengeId) and
                        (challenges.userId eq userId)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime VerificationResult.Invalid("Invalid challenge")

            val methodId = challenge[challenges.methodId]
            val attempts = challenge[challenges.attempts]
            val maxAttempts = challenge[challenges.maxAttempts]
            val expiresAt = challenge[challenges.expiresAt]
            val verifiedAt = challenge[challenges.verifiedAt]

            if (verifiedAt != null) {
                return@ensureMinimumResponseTime VerificationResult.Invalid("Challenge already verified")
            }

            if (nowLocal > expiresAt) {
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
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
                    timestamp = now,
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

            val codeHash = challenge[challenges.codeHash]
            val isValid = hashingService.verify(code, codeHash)

            if (!isValid) {
                db.transaction {
                    challenges.update({
                        (challenges.realmId eq realmId) and (challenges.id eq challengeId)
                    }) {
                        it[challenges.attempts] = attempts + 1
                    }
                }
                val remainingAttempts = maxAttempts - (attempts + 1)
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
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

            db.transaction {
                challenges.update({
                    (challenges.realmId eq realmId) and (challenges.id eq challengeId)
                }) {
                    it[challenges.verifiedAt] = nowLocal
                }

                methods.update({
                    (methods.realmId eq realmId) and (methods.id eq methodId)
                }) {
                    it[methods.lastUsedAt] = nowLocal
                }
            }

            eventBus.publish(MfaEvent.VerificationSuccess(
                eventId = UUID.randomUUID(),
                timestamp = now,
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
        return ensureMinimumResponseTime(250.milliseconds) {
            val now = CurrentKotlinInstant
            val nowLocal = now.toLocalDateTime(timeZone)
            val codeHash = hashingService.hash(code)

            val method = db.transaction {
                methods
                    .selectAll()
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.id eq methodId) and
                        (methods.methodType eq MfaMethodType.TOTP) and
                        (methods.isActive eq true)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime VerificationResult.Invalid("TOTP method not found or inactive")

            val encryptedSecret = method[methods.encryptedSecret]
                ?: return@ensureMinimumResponseTime VerificationResult.Invalid("No secret found")
            val nonce = method[methods.encryptionNonce]
                ?: return@ensureMinimumResponseTime VerificationResult.Invalid("No nonce found")

            val secret = secretEncryption.decrypt(EncryptedSecret(encryptedSecret, nonce))

            val codeAlreadyUsed = db.transaction {
                totpUsedCodes
                    .selectAll()
                    .where {
                        (totpUsedCodes.realmId eq realmId) and
                        (totpUsedCodes.userId eq userId) and
                        (totpUsedCodes.methodId eq methodId) and
                        (totpUsedCodes.codeHash eq codeHash)
                    }
                    .count() > 0
            }

            if (codeAlreadyUsed) {
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
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
                    timestamp = now,
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

            db.transaction {
                totpUsedCodes.insert {
                    it[totpUsedCodes.realmId] = this@DefaultMfaService.realmId
                    it[totpUsedCodes.userId] = userId
                    it[totpUsedCodes.methodId] = methodId
                    it[totpUsedCodes.codeHash] = codeHash
                    it[totpUsedCodes.usedAt] = nowLocal
                }

                methods.update({
                    (methods.realmId eq realmId) and (methods.id eq methodId)
                }) {
                    it[methods.lastUsedAt] = nowLocal
                }

                val cutoffTime = now.minus(90.seconds).toLocalDateTime(timeZone)
                totpUsedCodes.deleteWhere {
                    (totpUsedCodes.realmId eq this@DefaultMfaService.realmId) and
                    (totpUsedCodes.usedAt less cutoffTime)
                }
            }

            eventBus.publish(MfaEvent.VerificationSuccess(
                eventId = UUID.randomUUID(),
                timestamp = now,
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
        return db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.isActive eq true)
                }
                .map {
                    MfaMethodInfo(
                        id = it[methods.id],
                        type = it[methods.methodType],
                        identifier = it[methods.identifier],
                        isPrimary = it[methods.isPrimary],
                        lastUsedAt = it[methods.lastUsedAt]?.toInstant(timeZone)
                    )
                }
        }
    }

    override suspend fun removeMethod(userId: UUID, methodId: UUID) {
        val methodInfo = db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .singleOrNull()
                ?.let {
                    it[methods.methodType]
                }
        }

        db.transaction {
            methods.deleteWhere {
                (methods.realmId eq realmId) and
                (methods.userId eq userId) and
                (methods.id eq methodId)
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
        val oldPrimaryId = db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.isPrimary eq true)
                }
                .singleOrNull()
                ?.let { it[methods.id] }
        }

        val newMethodType = db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .singleOrNull()
                ?.let { it[methods.methodType] }
        }

        db.transaction {
            methods.update({
                (methods.realmId eq realmId) and (methods.userId eq userId)
            }) {
                it[methods.isPrimary] = false
            }

            methods.update({
                (methods.realmId eq realmId) and (methods.userId eq userId) and (methods.id eq methodId)
            }) {
                it[methods.isPrimary] = true
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
        if (!hasAnyMethod(userId)) {
            throw KodexThrowable.Authorization.InsufficientPermissions(
                requiredRole = "MFA_ENROLLED",
                userId = userId
            )
        }

        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)
        val codes = mutableListOf<String>()

        db.transaction {
            backupCodes.deleteWhere {
                (backupCodes.realmId eq realmId) and (backupCodes.userId eq userId)
            }

            repeat(config.backupCodesCount) {
                val code = TokenGenerator.generate(AlphanumericFormat(config.backupCodeLength, true))
                val codeHash = hashingService.hash(code)

                backupCodes.insert {
                    it[backupCodes.realmId] = this@DefaultMfaService.realmId
                    it[backupCodes.userId] = userId
                    it[backupCodes.codeHash] = codeHash
                    it[backupCodes.usedAt] = null
                    it[backupCodes.createdAt] = nowLocal
                }

                codes.add(code)
            }
        }

        eventBus.publish(MfaEvent.BackupCodesGenerated(
            eventId = UUID.randomUUID(),
            timestamp = now,
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
        return ensureMinimumResponseTime(250.milliseconds) {
            val now = CurrentKotlinInstant
            val nowLocal = now.toLocalDateTime(timeZone)

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

            val backupCodeRows = db.transaction {
                backupCodes
                    .selectAll()
                    .where {
                        (backupCodes.realmId eq realmId) and
                        (backupCodes.userId eq userId) and
                        (backupCodes.usedAt.isNull())
                    }
                    .toList()
            }

            for (backupCode in backupCodeRows) {
                val codeHash = backupCode[backupCodes.codeHash]
                if (hashingService.verify(code, codeHash)) {
                    val codeId = backupCode[backupCodes.id]
                    db.transaction {
                        backupCodes.update({
                            (backupCodes.realmId eq realmId) and (backupCodes.id eq codeId)
                        }) {
                            it[backupCodes.usedAt] = nowLocal
                        }
                    }

                    val remainingCodes = db.transaction {
                        backupCodes
                            .selectAll()
                            .where {
                                (backupCodes.realmId eq realmId) and
                                (backupCodes.userId eq userId) and
                                (backupCodes.usedAt.isNull())
                            }
                            .count()
                            .toInt()
                    }

                    eventBus.publish(MfaEvent.BackupCodeUsed(
                        eventId = UUID.randomUUID(),
                        timestamp = now,
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
        val session = sessionStore.getSession(sessionId)
            ?: return VerificationResult.Invalid("Invalid or expired MFA session")

        val result = when {
            methodId != null -> verifyTotp(session.userId, methodId, code, session.ipAddress)
            code.length == config.backupCodeLength ->
                verifyBackupCode(session.userId, code, session.ipAddress)
            else -> VerificationResult.Invalid("Invalid verification method or code")
        }

        if (result is VerificationResult.Success) {
            sessionStore.markAsVerified(sessionId)

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
        return db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.isActive eq true)
                }
                .count() > 0
        }
    }

    override fun isMfaRequired(userId: UUID): Boolean {
        return config.requireMfa && hasAnyMethod(userId)
    }

    override suspend fun trustDevice(
        userId: UUID,
        ipAddress: String?,
        userAgent: String?,
        deviceName: String?,
        expiresInDays: Int?
    ): UUID {
        val deviceFingerprint = DeviceFingerprint.generate(ipAddress, userAgent)

        val now = CurrentKotlinInstant
        val deviceId = db.transaction {
            val nowLocal = now.toLocalDateTime(timeZone)

            val effectiveExpiresInDays = expiresInDays
                ?: config.defaultTrustedDeviceExpiry?.inWholeDays?.toInt()

            val expiresAt = effectiveExpiresInDays?.let {
                now.plus(it.days).toLocalDateTime(timeZone)
            }

            trustedDevices.insert {
                it[trustedDevices.realmId] = this@DefaultMfaService.realmId
                it[trustedDevices.userId] = userId
                it[trustedDevices.deviceFingerprint] = deviceFingerprint
                it[trustedDevices.deviceName] = deviceName
                it[trustedDevices.ipAddress] = ipAddress
                it[trustedDevices.userAgent] = userAgent
                it[trustedDevices.trustedAt] = nowLocal
                it[trustedDevices.lastUsedAt] = null
                it[trustedDevices.expiresAt] = expiresAt
            }[trustedDevices.id]
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
        val deviceFingerprint = DeviceFingerprint.generate(ipAddress, userAgent)

        return db.transaction {
            val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

            val device = trustedDevices
                .selectAll()
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId) and
                    (trustedDevices.deviceFingerprint eq deviceFingerprint)
                }
                .singleOrNull()

            if (device == null) {
                return@transaction false
            }

            val expiresAt = device[trustedDevices.expiresAt]
            if (expiresAt != null && now > expiresAt) {
                return@transaction false
            }

            trustedDevices.update({
                (trustedDevices.realmId eq realmId) and
                (trustedDevices.userId eq userId) and
                (trustedDevices.deviceFingerprint eq deviceFingerprint)
            }) {
                it[trustedDevices.lastUsedAt] = now
            }

            true
        }
    }

    override suspend fun getTrustedDevices(userId: UUID): List<TrustedDeviceInfo> {
        return db.transaction {
            trustedDevices
                .selectAll()
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId)
                }
                .map {
                    TrustedDeviceInfo(
                        id = it[trustedDevices.id],
                        deviceFingerprint = it[trustedDevices.deviceFingerprint],
                        deviceName = it[trustedDevices.deviceName],
                        ipAddress = it[trustedDevices.ipAddress],
                        userAgent = it[trustedDevices.userAgent],
                        trustedAt = it[trustedDevices.trustedAt].toInstant(timeZone),
                        lastUsedAt = it[trustedDevices.lastUsedAt]?.toInstant(timeZone),
                        expiresAt = it[trustedDevices.expiresAt]?.toInstant(timeZone)
                    )
                }
        }
    }

    override suspend fun removeTrustedDevice(userId: UUID, deviceId: UUID) {
        val deviceInfo = db.transaction {
            trustedDevices
                .selectAll()
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId) and
                    (trustedDevices.id eq deviceId)
                }
                .singleOrNull()
                ?.let { it[trustedDevices.deviceFingerprint] }
        }

        db.transaction {
            trustedDevices.deleteWhere {
                (trustedDevices.realmId eq realmId) and
                (trustedDevices.userId eq userId) and
                (trustedDevices.id eq deviceId)
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
        db.transaction {
            trustedDevices.deleteWhere {
                (trustedDevices.realmId eq realmId) and (trustedDevices.userId eq userId)
            }
        }
    }

    override suspend fun forceRemoveMfaMethod(adminId: UUID, userId: UUID, methodId: UUID) {
        requireAdminRole(adminId)

        val methodInfo = db.transaction {
            methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .singleOrNull()
                ?.let {
                    it[methods.methodType]
                }
        }

        db.transaction {
            methods.deleteWhere {
                (methods.realmId eq realmId) and
                (methods.userId eq userId) and
                (methods.id eq methodId)
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

        db.transaction {
            methods.deleteWhere {
                (methods.realmId eq realmId) and (methods.userId eq userId)
            }
            backupCodes.deleteWhere {
                (backupCodes.realmId eq realmId) and (backupCodes.userId eq userId)
            }
            trustedDevices.deleteWhere {
                (trustedDevices.realmId eq realmId) and (trustedDevices.userId eq userId)
            }
        }
    }

    override suspend fun listUserMethods(adminId: UUID, userId: UUID): List<MfaMethodInfo> {
        requireAdminRole(adminId)

        return getMethods(userId)
    }

    override suspend fun getMfaStatistics(): MfaStatistics {
        val totalUsers = config.getTotalUsers?.invoke() ?: 0L
        return db.transaction {

            val usersWithMfa = methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.isActive eq true)
                }
                .map { it[methods.userId] }
                .distinct()
                .count()
                .toLong()

            val adoptionRate = if (totalUsers > 0) {
                (usersWithMfa.toDouble() / totalUsers.toDouble()) * 100.0
            } else 0.0

            val methodDistribution = methods
                .selectAll()
                .where {
                    (methods.realmId eq realmId) and
                    (methods.isActive eq true)
                }
                .groupBy { it[methods.methodType] }
                .mapValues { it.value.size.toLong() }

            val trustedDeviceCount = trustedDevices
                .selectAll()
                .where { trustedDevices.realmId eq realmId }
                .count()

            MfaStatistics(
                totalUsers = totalUsers,
                usersWithMfa = usersWithMfa,
                adoptionRate = adoptionRate,
                methodDistribution = methodDistribution,
                trustedDevices = trustedDeviceCount
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
