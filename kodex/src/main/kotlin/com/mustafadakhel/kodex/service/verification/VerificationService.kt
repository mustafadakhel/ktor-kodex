package com.mustafadakhel.kodex.service.verification

import java.util.UUID

/**
 * Service responsible for user verification status management.
 *
 * This service handles verification workflows, including email verification,
 * phone verification, and admin-initiated verification status changes.
 *
 * Current capabilities:
 * - Set verification status manually
 *
 * Future expansion:
 * - Send verification emails/SMS
 * - Verify with tokens
 * - Resend verification codes
 * - Check verification status with expiry
 */
public interface VerificationService {
    /**
     * Sets the verification status for a user.
     *
     * This is typically used for:
     * - Admin-initiated verification
     * - Completing email/phone verification flows
     * - Testing purposes
     *
     * @param userId The user whose verification status to update
     * @param verified True to mark as verified, false to mark as unverified
     */
    public fun setVerified(userId: UUID, verified: Boolean)
}
