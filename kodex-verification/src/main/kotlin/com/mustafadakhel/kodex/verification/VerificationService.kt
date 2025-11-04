package com.mustafadakhel.kodex.verification

import java.util.UUID

/**
 * Service for managing user contact verification.
 *
 * This service provides methods for managing verifiable contacts (email, phone, custom attributes),
 * checking verification status, and managing verification workflows.
 */
public interface VerificationService {

    // === Contact Management ===

    /**
     * Set or update a contact for a user.
     * If the contact value changes, verification status is reset.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier (email, phone, or custom attribute)
     * @param value The contact value (email address, phone number, etc.)
     */
    public suspend fun setContact(userId: UUID, identifier: ContactIdentifier, value: String)

    /**
     * Set email contact for a user.
     */
    public suspend fun setEmail(userId: UUID, email: String) {
        setContact(userId, ContactIdentifier(ContactType.EMAIL), email)
    }

    /**
     * Set phone contact for a user.
     */
    public suspend fun setPhone(userId: UUID, phone: String) {
        setContact(userId, ContactIdentifier(ContactType.PHONE), phone)
    }

    /**
     * Set a custom attribute contact for a user.
     */
    public suspend fun setCustomAttribute(userId: UUID, attributeKey: String, value: String) {
        setContact(userId, ContactIdentifier(ContactType.CUSTOM_ATTRIBUTE, attributeKey), value)
    }

    /**
     * Remove a contact for a user.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier to remove
     */
    public suspend fun removeContact(userId: UUID, identifier: ContactIdentifier)

    /**
     * Get a specific contact for a user.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier
     * @return The contact verification info, or null if not found
     */
    public fun getContact(userId: UUID, identifier: ContactIdentifier): ContactVerification?

    /**
     * Get all contacts for a user.
     *
     * @param userId The ID of the user
     * @return List of all contacts with their verification status
     */
    public fun getUserContacts(userId: UUID): List<ContactVerification>

    // === Verification Status ===

    /**
     * Check if a specific contact is verified.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier
     * @return true if the contact is verified, false otherwise
     */
    public fun isContactVerified(userId: UUID, identifier: ContactIdentifier): Boolean

    /**
     * Check if user satisfies verification policy and can login.
     *
     * This checks all required contacts based on the configured policy.
     *
     * @param userId The ID of the user
     * @return true if user can login, false if required contacts are unverified
     */
    public fun canLogin(userId: UUID): Boolean

    /**
     * Get complete verification status for a user.
     *
     * @param userId The ID of the user
     * @return Complete status with all contacts
     */
    public fun getStatus(userId: UUID): UserVerificationStatus

    /**
     * Get list of required contacts that are still unverified.
     *
     * @param userId The ID of the user
     * @return List of contact identifiers that are required but not verified
     */
    public fun getMissingVerifications(userId: UUID): List<ContactIdentifier>

    // === Token Operations ===

    /**
     * Send verification token for a specific contact.
     *
     * Uses the sender registered in the contact policy configuration.
     * Throws if no sender is configured for this contact type.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier
     * @param ipAddress Optional IP address for rate limiting
     * @return VerificationSendResult indicating success or rate limit exceeded
     * @throws IllegalStateException if no sender is configured for this contact type
     */
    public suspend fun sendVerification(
        userId: UUID,
        identifier: ContactIdentifier,
        ipAddress: String? = null
    ): VerificationSendResult

    /**
     * Verify a token for a specific contact.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier
     * @param token The verification token
     * @param ipAddress Optional IP address for rate limiting
     * @return VerificationResult indicating success, invalid token, or rate limit exceeded
     */
    public suspend fun verifyToken(
        userId: UUID,
        identifier: ContactIdentifier,
        token: String,
        ipAddress: String? = null
    ): VerificationResult

    /**
     * Resend verification for a contact.
     *
     * Uses the sender registered in the contact policy configuration.
     * Throws if no sender is configured for this contact type.
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier
     * @param ipAddress Optional IP address for rate limiting
     * @return VerificationSendResult indicating success or rate limit exceeded
     * @throws IllegalStateException if no sender is configured for this contact type
     */
    public suspend fun resendVerification(
        userId: UUID,
        identifier: ContactIdentifier,
        ipAddress: String? = null
    ): VerificationSendResult

    // === Manual Control ===

    /**
     * Manually mark a contact as verified (for admin/testing purposes).
     *
     * @param userId The ID of the user
     * @param identifier The contact identifier
     * @param verified Whether the contact should be marked as verified
     */
    public fun setVerified(userId: UUID, identifier: ContactIdentifier, verified: Boolean)
}
