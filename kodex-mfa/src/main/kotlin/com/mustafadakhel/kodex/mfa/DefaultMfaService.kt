@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.FailureReason
import com.mustafadakhel.kodex.jdbc.ConnectionScope
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.isNotNull
import com.mustafadakhel.kodex.jdbc.isNull
import com.mustafadakhel.kodex.jdbc.less
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
        algorithm = config.totp.algorithm,
        digits = config.totp.digits,
        period = config.totp.period
    )
    private val totpValidator = TotpValidator(totpGenerator, config.totp.timeStepWindow)
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

        val challengeId = db.transaction {
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, now)
            val codeHash = hashingService.hash(code)

            val methodId = insertReturningKey(methods, methods.id) {
                set(methods.realmId, realmId)
                set(methods.userId, userId)
                set(methods.methodType, MfaMethodType.EMAIL)
                set(methods.identifier, email)
                set(methods.encryptedSecret, null)
                set(methods.encryptionNonce, null)
                set(methods.isActive, false)
                set(methods.isPrimary, false)
                set(methods.enrolledAt, nowLocal)
            }

            insertReturningKey(challenges, challenges.id) {
                set(challenges.realmId, realmId)
                set(challenges.userId, userId)
                set(challenges.methodId, methodId)
                set(challenges.codeHash, codeHash)
                set(challenges.expiresAt, expiresAt)
                set(challenges.createdAt, nowLocal)
                set(challenges.attempts, 0)
                set(challenges.maxAttempts, config.maxVerifyAttempts)
            }
        }

        try {
            config.emailSender?.send(email, code)
        } catch (e: Exception) {
            db.transaction {
                deleteFrom(challenges).where { challenges.id eq challengeId }.execute()
            }
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

            data class ChallengeRow(
                val userId: UUID,
                val attempts: Int,
                val maxAttempts: Int,
                val expiresAt: kotlinx.datetime.LocalDateTime,
                val verifiedAt: kotlinx.datetime.LocalDateTime?,
                val codeHash: String,
                val methodId: UUID,
            )

            // Phase 1: Read + verify outside transaction (no lock held during CPU-heavy hash)
            val challenge = db.transaction {
                select(challenges)
                    .where { (challenges.realmId eq realmId) and (challenges.id eq challengeId) }
                    .singleOrNull { row ->
                        ChallengeRow(
                            userId = row[challenges.userId],
                            attempts = row[challenges.attempts],
                            maxAttempts = row[challenges.maxAttempts],
                            expiresAt = row[challenges.expiresAt],
                            verifiedAt = row[challenges.verifiedAt],
                            codeHash = row[challenges.codeHash],
                            methodId = row[challenges.methodId],
                        )
                    }
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid challenge")

            if (challenge.verifiedAt != null) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Challenge already verified")
            }

            if (nowLocal > challenge.expiresAt) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Expired("Code has expired")
            }

            if (challenge.attempts >= challenge.maxAttempts) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.RateLimitExceeded("Too many attempts")
            }

            val isValid = hashingService.verify(code, challenge.codeHash)

            if (!isValid) {
                db.transaction {
                    update(challenges) {
                        setExpression(challenges.attempts, "${challenges.attempts.name} + 1")
                        where {
                            (challenges.realmId eq realmId) and
                                (challenges.id eq challengeId) and
                                (challenges.attempts less challenge.maxAttempts)
                        }
                    }
                }
                eventBus.publish(MfaEvent.EnrollmentFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realmId,
                    userId = challenge.userId,
                    methodType = MfaMethodType.EMAIL,
                    reason = "Invalid code",
                    failureReason = FailureReason.INVALID_CREDENTIALS
                ))
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            // Phase 2: Single atomic transaction with forUpdate for all writes
            data class EnrollmentWriteResult(
                val methodId: UUID,
                val isPrimary: Boolean,
                val backupCodes: List<String>,
            )

            val writeResult = db.transaction {
                // Re-read with forUpdate to acquire row lock
                val lockedChallenge = select(challenges)
                    .where { (challenges.realmId eq realmId) and (challenges.id eq challengeId) }
                    .forUpdate()
                    .singleOrNull { row ->
                        ChallengeRow(
                            userId = row[challenges.userId],
                            attempts = row[challenges.attempts],
                            maxAttempts = row[challenges.maxAttempts],
                            expiresAt = row[challenges.expiresAt],
                            verifiedAt = row[challenges.verifiedAt],
                            codeHash = row[challenges.codeHash],
                            methodId = row[challenges.methodId],
                        )
                    }
                    ?: return@transaction null

                // Optimistic check: another thread may have verified between phase 1 and lock acquisition
                if (lockedChallenge.verifiedAt != null) {
                    return@transaction null
                }

                // Mark challenge verified
                update(challenges) {
                    set(challenges.verifiedAt, nowLocal)
                    where { (challenges.realmId eq realmId) and (challenges.id eq challengeId) }
                }

                val methodId = lockedChallenge.methodId

                // Check if user has any active method (inline, same connection — sees uncommitted writes)
                val hadAnyMethod = select(methods)
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.isActive eq true)
                    }
                    .any()

                // Activate the method
                update(methods) {
                    set(methods.isActive, true)
                    set(methods.isPrimary, !hadAnyMethod)
                    where { (methods.realmId eq realmId) and (methods.id eq methodId) }
                }

                // Generate backup codes inline (same transaction)
                val codes = generateBackupCodesInTransaction(userId, nowLocal)

                EnrollmentWriteResult(methodId, !hadAnyMethod, codes)
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Challenge already verified")

            eventBus.publish(MfaEvent.EnrollmentCompleted(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                methodId = writeResult.methodId,
                methodType = MfaMethodType.EMAIL,
                isPrimary = writeResult.isPrimary
            ))

            eventBus.publish(MfaEvent.BackupCodesGenerated(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                codeCount = config.backup.codeCount,
                actorId = userId
            ))

            EnrollmentVerificationResult.Success(writeResult.backupCodes)
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
            issuer = config.totp.issuer,
            accountName = accountName
        )
        val qrCodeDataUri = qrCodeGenerator.generateDataUri(qrCodeUri)

        val encrypted = secretEncryption.encrypt(secret)

        val methodId = db.transaction {
            val nowLocal = now.toLocalDateTime(timeZone)

            insertReturningKey(methods, methods.id) {
                set(methods.realmId, realmId)
                set(methods.userId, userId)
                set(methods.methodType, MfaMethodType.TOTP)
                set(methods.identifier, "TOTP")
                set(methods.encryptedSecret, encrypted.ciphertext)
                set(methods.encryptionNonce, encrypted.nonce)
                set(methods.isActive, false)
                set(methods.isPrimary, false)
                set(methods.enrolledAt, nowLocal)
            }
        }

        return TotpEnrollmentResult(
            methodId = methodId,
            secret = secret,
            qrCodeDataUri = qrCodeDataUri,
            issuer = config.totp.issuer,
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

            data class MethodRow(
                val encryptedSecret: String?,
                val encryptionNonce: String?,
                val isActive: Boolean,
            )

            // Phase 1: Read + validate TOTP outside transaction (no lock held during crypto)
            val method = db.transaction {
                select(methods)
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.id eq methodId) and
                        (methods.methodType eq MfaMethodType.TOTP) and
                        (methods.isActive eq false)
                    }
                    .singleOrNull { row ->
                        MethodRow(
                            encryptedSecret = row[methods.encryptedSecret],
                            encryptionNonce = row[methods.encryptionNonce],
                            isActive = row[methods.isActive],
                        )
                    }
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No pending TOTP enrollment for this method")

            val encryptedSecret = method.encryptedSecret
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No secret found")
            val nonce = method.encryptionNonce
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

            // Phase 2: Single atomic transaction with forUpdate for all writes
            data class TotpWriteResult(
                val isPrimary: Boolean,
                val backupCodes: List<String>,
            )

            val writeResult = db.transaction {
                // Re-read with forUpdate to acquire row lock
                val lockedMethod = select(methods)
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.id eq methodId) and
                        (methods.methodType eq MfaMethodType.TOTP)
                    }
                    .forUpdate()
                    .singleOrNull { row ->
                        MethodRow(
                            encryptedSecret = row[methods.encryptedSecret],
                            encryptionNonce = row[methods.encryptionNonce],
                            isActive = row[methods.isActive],
                        )
                    }
                    ?: return@transaction null

                // Optimistic check: another thread may have activated it already
                if (lockedMethod.isActive) {
                    return@transaction null
                }

                // Check if user has any active method (inline, same connection — sees uncommitted writes)
                val hadAnyMethod = select(methods)
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.isActive eq true)
                    }
                    .any()

                // Activate the method
                update(methods) {
                    set(methods.isActive, true)
                    set(methods.isPrimary, !hadAnyMethod)
                    set(methods.lastUsedAt, nowLocal)
                    where {
                        (methods.realmId eq realmId) and (methods.id eq methodId)
                    }
                }

                // Generate backup codes inline (same transaction)
                val codes = generateBackupCodesInTransaction(userId, nowLocal)

                TotpWriteResult(!hadAnyMethod, codes)
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Already verified")

            eventBus.publish(MfaEvent.EnrollmentCompleted(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                methodId = methodId,
                methodType = MfaMethodType.TOTP,
                isPrimary = writeResult.isPrimary
            ))

            eventBus.publish(MfaEvent.BackupCodesGenerated(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                codeCount = config.backup.codeCount,
                actorId = userId
            ))

            EnrollmentVerificationResult.Success(writeResult.backupCodes)
        }
    }

    override suspend fun challengeEmail(
        userId: UUID,
        methodId: UUID,
        ipAddress: String?
    ): ChallengeResult {
        val now = CurrentKotlinInstant
        val nowLocal = now.toLocalDateTime(timeZone)

        data class MethodRow(
            val methodType: MfaMethodType,
            val identifier: String?,
        )

        val method = db.transaction {
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId) and
                    (methods.isActive eq true)
                }
                .singleOrNull { row ->
                    MethodRow(
                        methodType = row[methods.methodType],
                        identifier = row[methods.identifier],
                    )
                }
        } ?: return ChallengeResult.Failed("MFA method not found or inactive")

        if (method.methodType != MfaMethodType.EMAIL) {
            return ChallengeResult.Failed("Method is not an email MFA method")
        }

        val email = method.identifier
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

        val challengeId = db.transaction {
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, now)
            val codeHash = hashingService.hash(code)

            insertReturningKey(challenges, challenges.id) {
                set(challenges.realmId, realmId)
                set(challenges.userId, userId)
                set(challenges.methodId, methodId)
                set(challenges.codeHash, codeHash)
                set(challenges.expiresAt, expiresAt)
                set(challenges.createdAt, nowLocal)
                set(challenges.attempts, 0)
                set(challenges.maxAttempts, config.maxVerifyAttempts)
            }
        }

        try {
            config.emailSender?.send(email, code)
        } catch (e: Exception) {
            db.transaction {
                deleteFrom(challenges).where { challenges.id eq challengeId }.execute()
            }
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(emailReservation.reservationId)
            ipReservation?.let { rateLimiter.releaseReservation(it.reservationId) }
            return ChallengeResult.Failed("Failed to send code: ${e.message}")
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

            data class ChallengeRow(
                val methodId: UUID,
                val attempts: Int,
                val maxAttempts: Int,
                val expiresAt: kotlinx.datetime.LocalDateTime,
                val verifiedAt: kotlinx.datetime.LocalDateTime?,
                val codeHash: String,
            )

            val challenge = db.transaction {
                select(challenges)
                    .where {
                        (challenges.realmId eq realmId) and
                        (challenges.id eq challengeId) and
                        (challenges.userId eq userId)
                    }
                    .singleOrNull { row ->
                        ChallengeRow(
                            methodId = row[challenges.methodId],
                            attempts = row[challenges.attempts],
                            maxAttempts = row[challenges.maxAttempts],
                            expiresAt = row[challenges.expiresAt],
                            verifiedAt = row[challenges.verifiedAt],
                            codeHash = row[challenges.codeHash],
                        )
                    }
            } ?: return@ensureMinimumResponseTime VerificationResult.Invalid("Invalid challenge")

            if (challenge.verifiedAt != null) {
                return@ensureMinimumResponseTime VerificationResult.Invalid("Challenge already verified")
            }

            if (nowLocal > challenge.expiresAt) {
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realmId,
                    userId = userId,
                    methodId = challenge.methodId,
                    challengeId = challengeId,
                    methodType = MfaMethodType.EMAIL,
                    reason = "Code has expired",
                    failureReason = FailureReason.TOKEN_EXPIRED,
                    attemptsRemaining = null,
                    sourceIp = ipAddress
                ))
                return@ensureMinimumResponseTime VerificationResult.Expired("Code has expired")
            }

            if (challenge.attempts >= challenge.maxAttempts) {
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

            val isValid = hashingService.verify(code, challenge.codeHash)

            if (!isValid) {
                db.transaction {
                    update(challenges) {
                        setExpression(challenges.attempts, "${challenges.attempts.name} + 1")
                        where {
                            (challenges.realmId eq realmId) and
                                (challenges.id eq challengeId) and
                                (challenges.attempts less challenge.maxAttempts)
                        }
                    }
                }
                val remainingAttempts = challenge.maxAttempts - (challenge.attempts + 1)
                eventBus.publish(MfaEvent.VerificationFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realmId,
                    userId = userId,
                    methodId = challenge.methodId,
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
                update(challenges) {
                    set(challenges.verifiedAt, nowLocal)
                    where { (challenges.realmId eq realmId) and (challenges.id eq challengeId) }
                }

                update(methods) {
                    set(methods.lastUsedAt, nowLocal)
                    where { (methods.realmId eq realmId) and (methods.id eq challenge.methodId) }
                }
            }

            eventBus.publish(MfaEvent.VerificationSuccess(
                eventId = UUID.randomUUID(),
                timestamp = now,
                realmId = realmId,
                userId = userId,
                methodId = challenge.methodId,
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

            data class MethodRow(
                val encryptedSecret: String?,
                val encryptionNonce: String?,
            )

            val method = db.transaction {
                select(methods)
                    .where {
                        (methods.realmId eq realmId) and
                        (methods.userId eq userId) and
                        (methods.id eq methodId) and
                        (methods.methodType eq MfaMethodType.TOTP) and
                        (methods.isActive eq true)
                    }
                    .singleOrNull { row ->
                        MethodRow(
                            encryptedSecret = row[methods.encryptedSecret],
                            encryptionNonce = row[methods.encryptionNonce],
                        )
                    }
            } ?: return@ensureMinimumResponseTime VerificationResult.Invalid("TOTP method not found or inactive")

            val encryptedSecret = method.encryptedSecret
                ?: return@ensureMinimumResponseTime VerificationResult.Invalid("No secret found")
            val nonce = method.encryptionNonce
                ?: return@ensureMinimumResponseTime VerificationResult.Invalid("No nonce found")

            val secret = secretEncryption.decrypt(EncryptedSecret(encryptedSecret, nonce))

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

            // Atomic insert — unique constraint on (realmId, userId, methodId, codeHash)
            // prevents replay. If insert returns 0 (conflict), code was already used.
            val claimed = db.transaction {
                val inserted = insertOrIgnore(
                    totpUsedCodes,
                    listOf(totpUsedCodes.realmId, totpUsedCodes.userId, totpUsedCodes.methodId, totpUsedCodes.codeHash)
                ) {
                    set(totpUsedCodes.realmId, realmId)
                    set(totpUsedCodes.userId, userId)
                    set(totpUsedCodes.methodId, methodId)
                    set(totpUsedCodes.codeHash, codeHash)
                    set(totpUsedCodes.usedAt, nowLocal)
                }
                inserted
            }

            if (claimed == 0) {
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

            db.transaction {

                update(methods) {
                    set(methods.lastUsedAt, nowLocal)
                    where { (methods.realmId eq realmId) and (methods.id eq methodId) }
                }

                val replayWindow = config.totp.period * (config.totp.timeStepWindow * 2 + 1)
                val cutoffTime = now.minus(replayWindow).toLocalDateTime(timeZone)
                deleteFrom(totpUsedCodes)
                    .where {
                        (totpUsedCodes.realmId eq realmId) and
                        (totpUsedCodes.usedAt less cutoffTime)
                    }
                    .execute()
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
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.isActive eq true)
                }
                .map { row ->
                    MfaMethodInfo(
                        id = row[methods.id],
                        type = row[methods.methodType],
                        identifier = row[methods.identifier],
                        isPrimary = row[methods.isPrimary],
                        lastUsedAt = row[methods.lastUsedAt]?.toInstant(timeZone)
                    )
                }
        }
    }

    override suspend fun removeMethod(userId: UUID, methodId: UUID) {
        val methodInfo = db.transaction {
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .singleOrNull { row -> row[methods.methodType] }
        }

        db.transaction {
            deleteFrom(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .execute()
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
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.isPrimary eq true)
                }
                .singleOrNull { row -> row[methods.id] }
        }

        val newMethodType = db.transaction {
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .singleOrNull { row -> row[methods.methodType] }
        }

        db.transaction {
            update(methods) {
                set(methods.isPrimary, false)
                where {
                    (methods.realmId eq realmId) and (methods.userId eq userId)
                }
            }

            update(methods) {
                set(methods.isPrimary, true)
                where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
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
            deleteFrom(backupCodes)
                .where {
                    (backupCodes.realmId eq realmId) and (backupCodes.userId eq userId)
                }
                .execute()

            repeat(config.backup.codeCount) {
                val code = TokenGenerator.generate(AlphanumericFormat(config.backup.codeLength, true))
                val codeHash = hashingService.hash(code)

                insertInto(backupCodes) {
                    set(backupCodes.realmId, realmId)
                    set(backupCodes.userId, userId)
                    set(backupCodes.codeHash, codeHash)
                    set(backupCodes.usedAt, null)
                    set(backupCodes.createdAt, nowLocal)
                }

                codes.add(code)
            }
        }

        eventBus.publish(MfaEvent.BackupCodesGenerated(
            eventId = UUID.randomUUID(),
            timestamp = now,
            realmId = realmId,
            userId = userId,
            codeCount = config.backup.codeCount,
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

            data class BackupCodeRow(val id: UUID, val codeHash: String)

            val backupCodeRows = db.transaction {
                select(backupCodes)
                    .where {
                        (backupCodes.realmId eq realmId) and
                        (backupCodes.userId eq userId) and
                        (backupCodes.usedAt.isNull())
                    }
                    .map { row ->
                        BackupCodeRow(
                            id = row[backupCodes.id],
                            codeHash = row[backupCodes.codeHash],
                        )
                    }
            }

            for (backupCode in backupCodeRows) {
                if (hashingService.verify(code, backupCode.codeHash)) {
                    val claimed = db.transaction {
                        update(backupCodes) {
                            set(backupCodes.usedAt, nowLocal)
                            where {
                                (backupCodes.realmId eq realmId) and
                                    (backupCodes.id eq backupCode.id) and
                                    (backupCodes.usedAt.isNull())
                            }
                        }
                    }

                    if (claimed == 0) {
                        return@ensureMinimumResponseTime VerificationResult.Invalid("Backup code already used")
                    }

                    val remainingCodes = db.transaction {
                        select(backupCodes)
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
                        codeId = backupCode.id,
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
            code.length == config.backup.codeLength ->
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
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.isActive eq true)
                }
                .any()
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

            insertReturningKey(trustedDevices, trustedDevices.id) {
                set(trustedDevices.realmId, realmId)
                set(trustedDevices.userId, userId)
                set(trustedDevices.deviceFingerprint, deviceFingerprint)
                set(trustedDevices.deviceName, deviceName)
                set(trustedDevices.ipAddress, ipAddress)
                set(trustedDevices.userAgent, userAgent)
                set(trustedDevices.trustedAt, nowLocal)
                set(trustedDevices.lastUsedAt, null)
                set(trustedDevices.expiresAt, expiresAt)
            }
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

            data class DeviceRow(
                val expiresAt: kotlinx.datetime.LocalDateTime?,
            )

            val device = select(trustedDevices)
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId) and
                    (trustedDevices.deviceFingerprint eq deviceFingerprint)
                }
                .singleOrNull { row ->
                    DeviceRow(expiresAt = row[trustedDevices.expiresAt])
                }

            if (device == null) {
                return@transaction false
            }

            if (device.expiresAt != null && now > device.expiresAt) {
                return@transaction false
            }

            update(trustedDevices) {
                set(trustedDevices.lastUsedAt, now)
                where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId) and
                    (trustedDevices.deviceFingerprint eq deviceFingerprint)
                }
            }

            true
        }
    }

    override suspend fun getTrustedDevices(userId: UUID): List<TrustedDeviceInfo> {
        return db.transaction {
            select(trustedDevices)
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId)
                }
                .map { row ->
                    TrustedDeviceInfo(
                        id = row[trustedDevices.id],
                        deviceFingerprint = row[trustedDevices.deviceFingerprint],
                        deviceName = row[trustedDevices.deviceName],
                        ipAddress = row[trustedDevices.ipAddress],
                        userAgent = row[trustedDevices.userAgent],
                        trustedAt = row[trustedDevices.trustedAt].toInstant(timeZone),
                        lastUsedAt = row[trustedDevices.lastUsedAt]?.toInstant(timeZone),
                        expiresAt = row[trustedDevices.expiresAt]?.toInstant(timeZone)
                    )
                }
        }
    }

    override suspend fun removeTrustedDevice(userId: UUID, deviceId: UUID) {
        val deviceInfo = db.transaction {
            select(trustedDevices)
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId) and
                    (trustedDevices.id eq deviceId)
                }
                .singleOrNull { row -> row[trustedDevices.deviceFingerprint] }
        }

        db.transaction {
            deleteFrom(trustedDevices)
                .where {
                    (trustedDevices.realmId eq realmId) and
                    (trustedDevices.userId eq userId) and
                    (trustedDevices.id eq deviceId)
                }
                .execute()
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
            deleteFrom(trustedDevices)
                .where {
                    (trustedDevices.realmId eq realmId) and (trustedDevices.userId eq userId)
                }
                .execute()
        }
    }

    override suspend fun forceRemoveMfaMethod(adminId: UUID, userId: UUID, methodId: UUID) {
        requireAdminRole(adminId)

        val methodInfo = db.transaction {
            select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .singleOrNull { row -> row[methods.methodType] }
        }

        db.transaction {
            deleteFrom(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.userId eq userId) and
                    (methods.id eq methodId)
                }
                .execute()
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
            deleteFrom(methods)
                .where { (methods.realmId eq realmId) and (methods.userId eq userId) }
                .execute()
            deleteFrom(backupCodes)
                .where { (backupCodes.realmId eq realmId) and (backupCodes.userId eq userId) }
                .execute()
            deleteFrom(trustedDevices)
                .where { (trustedDevices.realmId eq realmId) and (trustedDevices.userId eq userId) }
                .execute()
        }
    }

    override suspend fun listUserMethods(adminId: UUID, userId: UUID): List<MfaMethodInfo> {
        requireAdminRole(adminId)

        return getMethods(userId)
    }

    override suspend fun getMfaStatistics(): MfaStatistics {
        val totalUsers = config.getTotalUsers?.invoke() ?: 0L
        return db.transaction {

            val usersWithMfa = select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.isActive eq true)
                }
                .map { row -> row[methods.userId] }
                .distinct()
                .count()
                .toLong()

            val methodDistribution = select(methods)
                .where {
                    (methods.realmId eq realmId) and
                    (methods.isActive eq true)
                }
                .map { row -> row[methods.methodType] }
                .groupBy { it }
                .mapValues { it.value.size.toLong() }

            val trustedDeviceCount = select(trustedDevices)
                .where { trustedDevices.realmId eq realmId }
                .count()

            val adoptionRate = if (totalUsers > 0) {
                (usersWithMfa.toDouble() / totalUsers.toDouble()) * 100.0
            } else 0.0

            MfaStatistics(
                totalUsers = totalUsers,
                usersWithMfa = usersWithMfa,
                adoptionRate = adoptionRate,
                methodDistribution = methodDistribution,
                trustedDevices = trustedDeviceCount
            )
        }
    }

    @OptIn(InternalKodexApi::class)
    private fun ConnectionScope.generateBackupCodesInTransaction(
        userId: UUID,
        nowLocal: kotlinx.datetime.LocalDateTime,
    ): List<String> {
        deleteFrom(backupCodes)
            .where {
                (backupCodes.realmId eq realmId) and (backupCodes.userId eq userId)
            }
            .execute()

        val codes = mutableListOf<String>()
        repeat(config.backup.codeCount) {
            val code = TokenGenerator.generate(AlphanumericFormat(config.backup.codeLength, true))
            val codeHash = hashingService.hash(code)

            insertInto(backupCodes) {
                set(backupCodes.realmId, realmId)
                set(backupCodes.userId, userId)
                set(backupCodes.codeHash, codeHash)
                set(backupCodes.usedAt, null)
                set(backupCodes.createdAt, nowLocal)
            }

            codes.add(code)
        }
        return codes
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
