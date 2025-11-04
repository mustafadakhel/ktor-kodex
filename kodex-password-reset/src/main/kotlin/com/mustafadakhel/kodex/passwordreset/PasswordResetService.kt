package com.mustafadakhel.kodex.passwordreset

import java.util.UUID

/**
 * Service for handling password reset requests.
 */
public interface PasswordResetService {

    /**
     * Initiates a password reset request by generating and sending a reset token.
     *
     * @param identifier User email or phone number
     * @param contactType EMAIL or PHONE
     * @param ipAddress IP address for rate limiting
     * @return Result indicating success or failure
     */
    public suspend fun initiatePasswordReset(
        identifier: String,
        contactType: ContactType,
        ipAddress: String?
    ): PasswordResetResult

    /**
     * Verifies a password reset token is valid.
     *
     * @param token The reset token to verify
     * @return Result with userId if valid, or error reason
     */
    public suspend fun verifyResetToken(token: String): TokenVerificationResult

    /**
     * Marks a reset token as used after password has been reset.
     * The caller is responsible for actually updating the password via core services.
     *
     * @param token The reset token to mark as used
     * @return Result with userId if successful, or error
     */
    public suspend fun consumeResetToken(token: String): TokenConsumptionResult

    /**
     * Revokes all outstanding password reset tokens for a user.
     *
     * @param userId User ID
     */
    public suspend fun revokeAllResetTokens(userId: UUID)

    public enum class ContactType {
        EMAIL,
        PHONE
    }
}

/**
 * Result of a password reset operation.
 *
 * SECURITY NOTE: UserNotFound is intentionally not included to prevent user enumeration.
 * Always returns Success whether user exists or not (email only sent if user exists).
 */
public sealed interface PasswordResetResult {
    /**
     * Request processed successfully.
     * Does NOT indicate whether user exists - email only sent if registered.
     */
    public data object Success : PasswordResetResult

    /**
     * Rate limit exceeded for this request.
     */
    public data class RateLimitExceeded(val reason: String) : PasswordResetResult

    /**
     * Invalid token provided.
     */
    public data class InvalidToken(val reason: String) : PasswordResetResult

    /**
     * Failed to send reset notification.
     * Only returned if configured to expose send failures.
     */
    public data class SendFailed(val reason: String) : PasswordResetResult
}

/**
 * Result of token verification.
 */
public sealed interface TokenVerificationResult {
    public data class Valid(val userId: UUID) : TokenVerificationResult
    public data class Invalid(val reason: String) : TokenVerificationResult
}

/**
 * Result of token consumption (marking as used).
 */
public sealed interface TokenConsumptionResult {
    public data class Success(val userId: UUID) : TokenConsumptionResult
    public data class Invalid(val reason: String) : TokenConsumptionResult
}
