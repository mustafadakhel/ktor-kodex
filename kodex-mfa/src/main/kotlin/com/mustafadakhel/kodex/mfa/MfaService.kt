package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.mfa.database.MfaMethodType
import kotlinx.datetime.Instant
import java.util.UUID

public interface MfaService {

    public suspend fun enrollEmail(
        userId: UUID,
        email: String,
        ipAddress: String?
    ): EnrollmentResult

    public suspend fun verifyEmailEnrollment(
        userId: UUID,
        challengeId: UUID,
        code: String
    ): EnrollmentVerificationResult

    public suspend fun enrollTotp(userId: UUID, accountName: String): TotpEnrollmentResult

    public suspend fun verifyTotpEnrollment(
        userId: UUID,
        methodId: UUID,
        code: String
    ): EnrollmentVerificationResult

    public suspend fun challengeEmail(
        userId: UUID,
        methodId: UUID,
        ipAddress: String?
    ): ChallengeResult

    public suspend fun verifyChallenge(
        userId: UUID,
        challengeId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult

    public suspend fun verifyTotp(
        userId: UUID,
        methodId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult

    public fun getMethods(userId: UUID): List<MfaMethodInfo>

    public suspend fun removeMethod(userId: UUID, methodId: UUID)

    public suspend fun setPrimaryMethod(userId: UUID, methodId: UUID)

    public suspend fun generateBackupCodes(userId: UUID): List<String>

    public suspend fun verifyBackupCode(
        userId: UUID,
        code: String,
        ipAddress: String?
    ): VerificationResult

    public suspend fun verifyMfaSession(
        sessionId: String,
        code: String,
        methodId: UUID? = null
    ): VerificationResult

    public fun hasAnyMethod(userId: UUID): Boolean

    public fun isMfaRequired(userId: UUID): Boolean

    // Trusted Devices
    public suspend fun trustDevice(
        userId: UUID,
        ipAddress: String?,
        userAgent: String?,
        deviceName: String? = null,
        expiresInDays: Int? = null
    ): UUID

    public suspend fun isDeviceTrusted(
        userId: UUID,
        ipAddress: String?,
        userAgent: String?
    ): Boolean

    public suspend fun getTrustedDevices(userId: UUID): List<TrustedDeviceInfo>

    public suspend fun removeTrustedDevice(userId: UUID, deviceId: UUID)

    public suspend fun removeAllTrustedDevices(userId: UUID)

    // Admin Management
    public suspend fun forceRemoveMfaMethod(adminId: UUID, userId: UUID, methodId: UUID)

    public suspend fun disableMfaForUser(adminId: UUID, userId: UUID)

    public suspend fun listUserMethods(adminId: UUID, userId: UUID): List<MfaMethodInfo>

    // Statistics
    public suspend fun getMfaStatistics(): MfaStatistics
}

public sealed interface EnrollmentResult {
    public data class CodeSent(val challengeId: UUID) : EnrollmentResult
    public data class RateLimitExceeded(val reason: String, val retryAfter: Instant?) : EnrollmentResult
    public data class Cooldown(val reason: String, val retryAfter: Instant) : EnrollmentResult
    public data class Failed(val reason: String) : EnrollmentResult
}

public data class TotpEnrollmentResult(
    val methodId: UUID,
    val secret: String,
    val qrCodeDataUri: String,
    val issuer: String,
    val accountName: String
)

public sealed interface EnrollmentVerificationResult {
    public data class Success(val backupCodes: List<String>) : EnrollmentVerificationResult
    public data class Invalid(val reason: String) : EnrollmentVerificationResult
    public data class Expired(val reason: String) : EnrollmentVerificationResult
    public data class RateLimitExceeded(val reason: String) : EnrollmentVerificationResult
}

public sealed interface ChallengeResult {
    public data class Success(val challengeId: UUID) : ChallengeResult
    public data class RateLimitExceeded(val reason: String, val retryAfter: Instant?) : ChallengeResult
    public data class Cooldown(val reason: String, val retryAfter: Instant) : ChallengeResult
    public data class Failed(val reason: String) : ChallengeResult
}

public sealed interface VerificationResult {
    public data object Success : VerificationResult
    public data class Invalid(val reason: String) : VerificationResult
    public data class Expired(val reason: String) : VerificationResult
    public data class RateLimitExceeded(val reason: String) : VerificationResult
}

public data class MfaMethodInfo(
    val id: UUID,
    val type: MfaMethodType,
    val identifier: String?,
    val isPrimary: Boolean,
    val lastUsedAt: Instant?
)

public data class TrustedDeviceInfo(
    val id: UUID,
    val deviceFingerprint: String,
    val deviceName: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val trustedAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?
)

public data class MfaStatistics(
    val totalUsers: Long,
    val usersWithMfa: Long,
    val adoptionRate: Double,
    val methodDistribution: Map<MfaMethodType, Long>,
    val trustedDevices: Long
)
