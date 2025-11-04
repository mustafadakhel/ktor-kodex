package com.mustafadakhel.kodex.verification

import kotlinx.datetime.LocalDateTime
import java.util.UUID

/**
 * Verification status for a single contact.
 */
public data class ContactVerification(
    val identifier: ContactIdentifier,
    val contactValue: String,
    val isVerified: Boolean,
    val verifiedAt: LocalDateTime?
)

/**
 * Complete verification status for a user.
 * Contains all contacts and their verification state.
 */
public data class UserVerificationStatus(
    val userId: UUID,
    val contacts: Map<String, ContactVerification>
) {
    /**
     * Get verification status for a specific contact.
     */
    public fun getContact(identifier: ContactIdentifier): ContactVerification? = contacts[identifier.key]

    /**
     * Check if a specific contact is verified.
     */
    public fun isContactVerified(identifier: ContactIdentifier): Boolean =
        contacts[identifier.key]?.isVerified ?: false

    /**
     * Get all verified contacts.
     */
    public fun getVerifiedContacts(): List<ContactVerification> =
        contacts.values.filter { it.isVerified }

    /**
     * Get all unverified contacts.
     */
    public fun getUnverifiedContacts(): List<ContactVerification> =
        contacts.values.filter { !it.isVerified }
}

/**
 * Result of sending a verification token.
 */
public sealed interface VerificationSendResult {
    /**
     * Token was successfully sent.
     */
    public data class Success(val token: String) : VerificationSendResult

    /**
     * Rate limit exceeded for sending verification tokens.
     */
    public data class RateLimitExceeded(val reason: String) : VerificationSendResult

    /**
     * Failed to send verification token (email/SMS provider failure).
     * User should retry without being rate limited.
     */
    public data class SendFailed(val reason: String) : VerificationSendResult
}

/**
 * Result of verifying a token.
 */
public sealed interface VerificationResult {
    /**
     * Token verified successfully, contact marked as verified.
     */
    public data object Success : VerificationResult

    /**
     * Token is invalid, expired, or already used.
     */
    public data class Invalid(val reason: String) : VerificationResult

    /**
     * Rate limit exceeded for verification attempts.
     */
    public data class RateLimitExceeded(val reason: String) : VerificationResult
}
