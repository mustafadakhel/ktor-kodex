package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.mfa.database.MfaBackupCodes
import com.mustafadakhel.kodex.mfa.database.MfaChallenges
import com.mustafadakhel.kodex.mfa.database.MfaMethodType
import com.mustafadakhel.kodex.mfa.database.MfaMethods
import com.mustafadakhel.kodex.mfa.encryption.EncryptedSecret
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.sender.MfaCodeSender
import com.mustafadakhel.kodex.mfa.totp.QrCodeGenerator
import com.mustafadakhel.kodex.mfa.totp.TotpGenerator
import com.mustafadakhel.kodex.mfa.totp.TotpValidator
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.tokens.security.RateLimitResult
import com.mustafadakhel.kodex.tokens.security.RateLimiter
import com.mustafadakhel.kodex.tokens.token.AlphanumericFormat
import com.mustafadakhel.kodex.tokens.token.NumericFormat
import com.mustafadakhel.kodex.tokens.token.TokenGenerator
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal class DefaultMfaService(
    private val config: MfaConfig,
    private val timeZone: TimeZone,
    private val hashingService: HashingService,
    private val secretEncryption: SecretEncryption
) : MfaService {

    private val rateLimiter = RateLimiter()
    private val totpGenerator = TotpGenerator(
        algorithm = config.totpAlgorithm,
        digits = config.totpDigits,
        period = config.totpPeriod
    )
    private val totpValidator = TotpValidator(totpGenerator, config.totpTimeStepWindow)
    private val qrCodeGenerator = QrCodeGenerator()

    override suspend fun enrollEmail(
        userId: UUID,
        email: String,
        ipAddress: String?
    ): EnrollmentResult {
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
            return when (val result = userReservation.result) {
                is RateLimitResult.Exceeded -> EnrollmentResult.RateLimitExceeded(result.reason, null)
                is RateLimitResult.Cooldown -> EnrollmentResult.Cooldown(result.reason, result.retryAfter!!)
                else -> EnrollmentResult.Failed("Rate limit check failed")
            }
        }

        val emailReservation = rateLimiter.checkAndReserve(
            emailKey,
            config.maxEnrollAttemptsPerContact,
            config.enrollRateLimitWindow
        )

        if (!emailReservation.isAllowed()) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            return when (val result = emailReservation.result) {
                is RateLimitResult.Exceeded -> EnrollmentResult.RateLimitExceeded(result.reason, null)
                else -> EnrollmentResult.Failed("Rate limit check failed")
            }
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
            return when (val result = ipReservation.result) {
                is RateLimitResult.Exceeded -> EnrollmentResult.RateLimitExceeded(result.reason, null)
                else -> EnrollmentResult.Failed("Rate limit check failed")
            }
        }

        val code = TokenGenerator.generate(NumericFormat(config.codeLength))

        try {
            config.emailSender?.send(email, code)
        } catch (e: Exception) {
            rateLimiter.releaseReservation(userReservation.reservationId)
            rateLimiter.releaseReservation(emailReservation.reservationId)
            ipReservation?.let { rateLimiter.releaseReservation(it.reservationId) }
            return EnrollmentResult.Failed("Failed to send code: ${e.message}")
        }

        val challengeId = kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)
            val expiresAt = ExpirationCalculator.calculateExpiration(config.codeExpiration, timeZone, Clock.System.now())
            val codeHash = hashingService.hash(code)

            val tempMethodId = UUID.randomUUID()

            MfaChallenges.insert {
                it[MfaChallenges.userId] = userId
                it[MfaChallenges.methodId] = tempMethodId
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
                    .where { MfaChallenges.id eq challengeId }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid challenge")

            val userId = challenge[MfaChallenges.userId]
            val attempts = challenge[MfaChallenges.attempts]
            val maxAttempts = challenge[MfaChallenges.maxAttempts]
            val expiresAt = challenge[MfaChallenges.expiresAt]
            val verifiedAt = challenge[MfaChallenges.verifiedAt]
            val now = Clock.System.now().toLocalDateTime(timeZone)

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
                    MfaChallenges.update({ MfaChallenges.id eq challengeId }) {
                        it[MfaChallenges.attempts] = attempts + 1
                    }
                }
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            kodexTransaction {
                MfaChallenges.update({ MfaChallenges.id eq challengeId }) {
                    it[MfaChallenges.verifiedAt] = now
                }

                MfaMethods.insert {
                    it[MfaMethods.userId] = userId
                    it[MfaMethods.methodType] = MfaMethodType.EMAIL
                    it[MfaMethods.identifier] = "email"
                    it[MfaMethods.encryptedSecret] = null
                    it[MfaMethods.encryptionNonce] = null
                    it[MfaMethods.isActive] = true
                    it[MfaMethods.isPrimary] = !hasAnyMethod(userId)
                    it[MfaMethods.enrolledAt] = now
                }
            }

            val backupCodes = generateBackupCodes(userId)

            EnrollmentVerificationResult.Success(backupCodes)
        }
    }

    override suspend fun enrollTotp(userId: UUID, accountName: String): TotpEnrollmentResult {
        val secret = totpGenerator.generateSecret()
        val qrCodeUri = totpGenerator.generateQrCodeUri(
            secret = secret,
            issuer = config.totpIssuer,
            accountName = accountName
        )
        val qrCodeDataUri = qrCodeGenerator.generateDataUri(qrCodeUri)

        val encrypted = secretEncryption.encrypt(secret)

        kodexTransaction {
            val now = Clock.System.now().toLocalDateTime(timeZone)

            MfaMethods.insert {
                it[MfaMethods.userId] = userId
                it[methodType] = MfaMethodType.TOTP
                it[identifier] = "TOTP"
                it[encryptedSecret] = encrypted.ciphertext
                it[encryptionNonce] = encrypted.nonce
                it[isActive] = false
                it[isPrimary] = false
                it[enrolledAt] = now
            }
        }

        return TotpEnrollmentResult(
            secret = secret,
            qrCodeDataUri = qrCodeDataUri,
            issuer = config.totpIssuer,
            accountName = accountName
        )
    }

    override suspend fun verifyTotpEnrollment(
        userId: UUID,
        code: String
    ): EnrollmentVerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            val method = kodexTransaction {
                MfaMethods
                    .selectAll()
                    .where {
                        (MfaMethods.userId eq userId) and
                        (MfaMethods.methodType eq MfaMethodType.TOTP) and
                        (MfaMethods.isActive eq false)
                    }
                    .singleOrNull()
            } ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No pending TOTP enrollment")

            val encryptedSecret = method[MfaMethods.encryptedSecret]
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No secret found")
            val nonce = method[MfaMethods.encryptionNonce]
                ?: return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("No nonce found")

            val secret = secretEncryption.decrypt(EncryptedSecret(encryptedSecret, nonce))

            val isValid = totpValidator.validate(secret, code)

            if (!isValid) {
                return@ensureMinimumResponseTime EnrollmentVerificationResult.Invalid("Invalid code")
            }

            kodexTransaction {
                val now = Clock.System.now().toLocalDateTime(timeZone)

                MfaMethods.update({
                    (MfaMethods.userId eq userId) and
                    (MfaMethods.methodType eq MfaMethodType.TOTP) and
                    (MfaMethods.isActive eq false)
                }) {
                    it[isActive] = true
                    it[isPrimary] = !hasAnyMethod(userId)
                    it[lastUsedAt] = now
                }
            }

            val backupCodes = generateBackupCodes(userId)

            EnrollmentVerificationResult.Success(backupCodes)
        }
    }

    override suspend fun challengeEmail(
        userId: UUID,
        methodId: UUID,
        ipAddress: String?
    ): ChallengeResult {
        TODO("Implement challengeEmail for authentication flow")
    }

    override suspend fun verifyChallenge(
        userId: UUID,
        challengeId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult {
        TODO("Implement verifyChallenge for authentication flow")
    }

    override suspend fun verifyTotp(
        userId: UUID,
        methodId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult {
        TODO("Implement verifyTotp for authentication flow")
    }

    override fun getMethods(userId: UUID): List<MfaMethodInfo> {
        return kodexTransaction {
            MfaMethods
                .selectAll()
                .where { (MfaMethods.userId eq userId) and (MfaMethods.isActive eq true) }
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
        kodexTransaction {
            MfaMethods.deleteWhere {
                (MfaMethods.userId eq userId) and (MfaMethods.id eq methodId)
            }
        }
    }

    override suspend fun setPrimaryMethod(userId: UUID, methodId: UUID) {
        kodexTransaction {
            MfaMethods.update({ MfaMethods.userId eq userId }) {
                it[isPrimary] = false
            }

            MfaMethods.update({
                (MfaMethods.userId eq userId) and (MfaMethods.id eq methodId)
            }) {
                it[isPrimary] = true
            }
        }
    }

    override suspend fun generateBackupCodes(userId: UUID): List<String> {
        val codes = mutableListOf<String>()

        kodexTransaction {
            MfaBackupCodes.deleteWhere { MfaBackupCodes.userId eq userId }

            val now = Clock.System.now().toLocalDateTime(timeZone)

            repeat(config.backupCodesCount) {
                val code = TokenGenerator.generate(AlphanumericFormat(config.backupCodeLength, true))
                val codeHash = hashingService.hash(code)

                MfaBackupCodes.insert {
                    it[MfaBackupCodes.userId] = userId
                    it[MfaBackupCodes.codeHash] = codeHash
                    it[usedAt] = null
                    it[createdAt] = now
                }

                codes.add(code)
            }
        }

        return codes
    }

    override suspend fun verifyBackupCode(
        userId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult {
        return ensureMinimumResponseTime(100.milliseconds) {
            val backupCodes = kodexTransaction {
                MfaBackupCodes
                    .selectAll()
                    .where { (MfaBackupCodes.userId eq userId) and (MfaBackupCodes.usedAt.isNull()) }
                    .toList()
            }

            for (backupCode in backupCodes) {
                val codeHash = backupCode[MfaBackupCodes.codeHash]
                if (hashingService.verify(code, codeHash)) {
                    kodexTransaction {
                        val now = Clock.System.now().toLocalDateTime(timeZone)
                        MfaBackupCodes.update({ MfaBackupCodes.id eq backupCode[MfaBackupCodes.id] }) {
                            it[usedAt] = now
                        }
                    }
                    return@ensureMinimumResponseTime VerificationResult.Success
                }
            }

            VerificationResult.Invalid("Invalid backup code")
        }
    }

    override fun hasAnyMethod(userId: UUID): Boolean {
        return kodexTransaction {
            MfaMethods
                .selectAll()
                .where { (MfaMethods.userId eq userId) and (MfaMethods.isActive eq true) }
                .count() > 0
        }
    }

    override fun isMfaRequired(userId: UUID): Boolean {
        return config.requireMfa && hasAnyMethod(userId)
    }

    private suspend fun <T> ensureMinimumResponseTime(
        minimumTime: Duration,
        block: suspend () -> T
    ): T {
        val startTime = Clock.System.now()
        val result = block()
        val elapsed = Clock.System.now() - startTime
        val remaining = minimumTime - elapsed

        if (remaining.isPositive()) {
            delay(remaining.inWholeMilliseconds)
        }

        return result
    }
}
