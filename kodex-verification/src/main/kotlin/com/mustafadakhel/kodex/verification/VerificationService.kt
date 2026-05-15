package com.mustafadakhel.kodex.verification

import java.util.UUID

/**
 * Service for managing user contact verification.
 *
 * This service provides methods for managing verifiable contacts (email, phone, custom attributes),
 * checking verification status, and managing verification workflows.
 */
public interface VerificationService {

    /**
     * Set or update a contact for a user.
     * If the contact value changes, verification status is reset.
     *
     * @param userId The ID of the user
     * @param contactType The contact type (Email, Phone, or CustomAttribute)
     * @param value The contact value (email address, phone number, etc.)
     */
    public suspend fun setContact(userId: UUID, contactType: ContactType, value: String)

    public suspend fun setEmail(userId: UUID, email: String) {
        setContact(userId, ContactType.Email, email)
    }

    public suspend fun setPhone(userId: UUID, phone: String) {
        setContact(userId, ContactType.Phone, phone)
    }

    public suspend fun setCustomAttribute(userId: UUID, attributeKey: String, value: String) {
        setContact(userId, ContactType.CustomAttribute(attributeKey), value)
    }

    /**
     * Remove a contact for a user.
     *
     * @param userId The ID of the user
     * @param contactType The contact type to remove
     */
    public suspend fun removeContact(userId: UUID, contactType: ContactType)

    /**
     * Get a specific contact for a user.
     *
     * @param userId The ID of the user
     * @param contactType The contact type
     * @return The contact verification info, or null if not found
     */
    public fun getContact(userId: UUID, contactType: ContactType): ContactVerification?

    /**
     * Get all contacts for a user.
     *
     * @param userId The ID of the user
     * @return List of all contacts with their verification status
     */
    public fun getUserContacts(userId: UUID): List<ContactVerification>

    /**
     * Check if a specific contact is verified.
     *
     * @param userId The ID of the user
     * @param contactType The contact type
     * @return true if the contact is verified, false otherwise
     */
    public fun isContactVerified(userId: UUID, contactType: ContactType): Boolean

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
     * @return List of contact types that are required but not verified
     */
    public fun getMissingVerifications(userId: UUID): List<ContactType>

    /**
     * Send verification token for a specific contact.
     *
     * Uses the sender registered in the contact policy configuration.
     * Throws if no sender is configured for this contact type.
     *
     * @param userId The ID of the user
     * @param contactType The contact type
     * @param ipAddress Optional IP address for rate limiting
     * @return VerificationSendResult indicating success or rate limit exceeded
     * @throws IllegalStateException if no sender is configured for this contact type
     */
    public suspend fun sendVerification(
        userId: UUID,
        contactType: ContactType,
        ipAddress: String? = null
    ): VerificationSendResult

    /**
     * Verify a token for a specific contact.
     *
     * @param userId The ID of the user
     * @param contactType The contact type
     * @param token The verification token
     * @param ipAddress Optional IP address for rate limiting
     * @return VerificationResult indicating success, invalid token, or rate limit exceeded
     */
    public suspend fun verifyToken(
        userId: UUID,
        contactType: ContactType,
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
     * @param contactType The contact type
     * @param ipAddress Optional IP address for rate limiting
     * @return VerificationSendResult indicating success or rate limit exceeded
     * @throws IllegalStateException if no sender is configured for this contact type
     */
    public suspend fun resendVerification(
        userId: UUID,
        contactType: ContactType,
        ipAddress: String? = null
    ): VerificationSendResult

    /**
     * Manually mark a contact as verified (for admin/testing purposes).
     *
     * @param userId The ID of the user
     * @param contactType The contact type
     * @param verified Whether the contact should be marked as verified
     */
    public fun setVerified(userId: UUID, contactType: ContactType, verified: Boolean)
}
