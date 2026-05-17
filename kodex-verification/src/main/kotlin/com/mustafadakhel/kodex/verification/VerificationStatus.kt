package com.mustafadakhel.kodex.verification

import kotlinx.datetime.LocalDateTime
import java.util.UUID

public data class ContactVerification(
    val contactType: ContactType,
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
    public fun getContact(contactType: ContactType): ContactVerification? = contacts[contactType.key]

    public fun isContactVerified(contactType: ContactType): Boolean =
        contacts[contactType.key]?.isVerified ?: false

    public fun getVerifiedContacts(): List<ContactVerification> =
        contacts.values.filter { it.isVerified }

    public fun getUnverifiedContacts(): List<ContactVerification> =
        contacts.values.filter { !it.isVerified }
}

public sealed interface VerificationSendResult {
    public data object Success : VerificationSendResult

    public data class RateLimitExceeded(val reason: String) : VerificationSendResult

    /**
     * Failed to send verification token (email/SMS provider failure).
     * User should retry without being rate limited.
     */
    public data class SendFailed(val reason: String) : VerificationSendResult

    public data class DependencyNotMet(
        val missingDependencies: List<ContactType>,
        val message: String
    ) : VerificationSendResult
}

public sealed interface VerificationResult {
    public data object Success : VerificationResult

    /**
     * Token is invalid, expired, or already used.
     */
    public data class Invalid(val reason: String) : VerificationResult

    public data class RateLimitExceeded(val reason: String) : VerificationResult
}
