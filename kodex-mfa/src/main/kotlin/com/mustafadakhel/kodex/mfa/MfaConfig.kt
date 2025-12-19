package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.mfa.encryption.AesGcmSecretEncryption
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.sender.MfaCodeSender
import com.mustafadakhel.kodex.mfa.totp.TotpAlgorithm
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.validation.ConfigValidationResult
import com.mustafadakhel.kodex.validation.ValidatableConfig
import com.mustafadakhel.kodex.validation.validate
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@KtorDsl
public class MfaConfig : ExtensionConfig(), ValidatableConfig {

    public var requireMfa: Boolean = false
    public var requiredRolesForMfa: Set<String> = emptySet()

    public var codeLength: Int = 6
    public var codeExpiration: Duration = 10.minutes

    public var automaticCleanup: Boolean = true
    public var cleanupInterval: Duration = 1.hours
    public var inactiveEnrollmentExpiration: Duration = 24.hours

    public var autoTrustDeviceAfterVerification: Boolean = true
    public var defaultTrustedDeviceExpiry: Duration? = 30.days

    public var maxEnrollAttemptsPerUser: Int = 3
    public var maxEnrollAttemptsPerContact: Int = 3
    public var maxEnrollAttemptsPerIp: Int = 5
    public var maxChallengeAttemptsPerUser: Int = 5
    public var maxChallengeAttemptsPerContact: Int = 3
    public var maxChallengeAttemptsPerIp: Int = 10
    public var maxVerifyAttempts: Int = 5
    public var maxBackupCodeAttemptsPerUser: Int = 10
    public var maxBackupCodeAttemptsPerIp: Int = 15

    public var enrollRateLimitWindow: Duration = 15.minutes
    public var challengeRateLimitWindow: Duration = 15.minutes
    public var verifyRateLimitWindow: Duration = 5.minutes
    public var backupCodeRateLimitWindow: Duration = 5.minutes
    public var enrollCooldownPeriod: Duration = 30.seconds
    public var challengeCooldownPeriod: Duration = 30.seconds

    public var sessionExpiration: Duration = 5.minutes
    public var maxActiveSessions: Int = 3

    public var emailSender: MfaCodeSender? = null

    public var totpEnabled: Boolean = true
    public var totpIssuer: String = "KodexAuth"
    public var totpAlgorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    public var totpDigits: Int = 6
    public var totpPeriod: Duration = 30.seconds
    public var totpTimeStepWindow: Int = 1

    public var backupCodesCount: Int = 10
    public var backupCodeLength: Int = 8

    public var secretEncryption: SecretEncryption? = null
    public var hashingService: HashingService? = null

    /** Function to check if a user has a specific role (required for admin operations) */
    public var userHasRole: (suspend (userId: java.util.UUID, role: String) -> Boolean)? = null

    /** Function to get the total number of users (used for MFA statistics) */
    public var getTotalUsers: (suspend () -> Long)? = null

    public fun emailMfa(block: EmailMfaConfig.() -> Unit) {
        val config = EmailMfaConfig().apply(block)
        emailSender = config.sender
    }

    public fun totpMfa(block: TotpMfaConfig.() -> Unit) {
        val config = TotpMfaConfig().apply(block)
        totpEnabled = config.enabled
        totpIssuer = config.issuer
        totpAlgorithm = config.algorithm
        totpDigits = config.digits
        totpPeriod = config.period
        totpTimeStepWindow = config.timeStepWindow
    }

    public fun backupCodes(block: BackupCodesConfig.() -> Unit) {
        val config = BackupCodesConfig().apply(block)
        backupCodesCount = config.codeCount
        backupCodeLength = config.codeLength
    }

    public fun encryption(block: EncryptionConfig.() -> Unit) {
        val config = EncryptionConfig().apply(block)
        secretEncryption = config.secretEncryption
    }

    public fun requireMfaForRoles(vararg roles: String) {
        requiredRolesForMfa = roles.toSet()
    }

    override fun validate(): ConfigValidationResult = validate {
        require(codeLength in 4..10) { "codeLength must be between 4 and 10" }
        require(codeExpiration.inWholeMinutes in 1..30) { "codeExpiration must be 1-30 minutes" }
        require(maxEnrollAttemptsPerUser > 0) { "maxEnrollAttemptsPerUser must be positive" }
        require(maxVerifyAttempts > 0) { "maxVerifyAttempts must be positive" }
        require(totpDigits in 6..8) { "totpDigits must be 6, 7, or 8" }
        require(totpPeriod.inWholeSeconds in 15..120) { "totpPeriod must be 15-120 seconds" }
        require(totpTimeStepWindow in 0..2) { "totpTimeStepWindow must be 0-2" }
        require(backupCodesCount in 5..20) { "backupCodesCount must be 5-20" }
        require(backupCodeLength in 6..12) { "backupCodeLength must be 6-12" }
        require(secretEncryption != null) { "secretEncryption must be configured" }
        require(hashingService != null) { "hashingService must be configured" }
        require(cleanupInterval.inWholeMinutes >= 1) { "cleanupInterval must be at least 1 minute" }
        require(inactiveEnrollmentExpiration.inWholeHours >= 1) { "inactiveEnrollmentExpiration must be at least 1 hour" }
        defaultTrustedDeviceExpiry?.let { expiry ->
            require(expiry.inWholeHours >= 1) { "defaultTrustedDeviceExpiry must be at least 1 hour if specified" }
        }
    }

    override fun build(context: ExtensionContext): MfaExtension {
        val validationResult = validate()
        if (!validationResult.isValid()) {
            throw IllegalStateException(
                "MFA configuration validation failed:\n" +
                validationResult.errors().joinToString("\n") { "  - $it" }
            )
        }

        return MfaExtension(
            config = this,
            timeZone = context.timeZone,
            hashingService = hashingService!!,
            secretEncryption = secretEncryption!!,
            eventBus = context.eventBus,
            realmId = context.realm.owner,
            rateLimiter = context.rateLimiter
        )
    }
}

@KtorDsl
public class EmailMfaConfig {
    public var enabled: Boolean = true
    public lateinit var sender: MfaCodeSender
}

@KtorDsl
public class TotpMfaConfig {
    public var enabled: Boolean = true
    public var issuer: String = "KodexAuth"
    public var algorithm: TotpAlgorithm = TotpAlgorithm.SHA1
    public var digits: Int = 6
    public var period: Duration = 30.seconds
    public var timeStepWindow: Int = 1
}

@KtorDsl
public class BackupCodesConfig {
    public var codeCount: Int = 10
    public var codeLength: Int = 8
}

@KtorDsl
public class EncryptionConfig {
    public var secretEncryption: SecretEncryption? = null

    public fun aesGcm(masterKeyHex: String) {
        secretEncryption = AesGcmSecretEncryption(
            AesGcmSecretEncryption.fromHexKey(masterKeyHex)
        )
    }

    public fun aesGcmBase64(masterKeyBase64: String) {
        secretEncryption = AesGcmSecretEncryption(
            AesGcmSecretEncryption.fromBase64Key(masterKeyBase64)
        )
    }
}
