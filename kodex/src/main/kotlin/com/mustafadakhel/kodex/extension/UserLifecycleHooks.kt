package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import java.util.UUID

/**
 * Extension interface for user lifecycle events.
 * Implementations can validate, transform, or reject user operations.
 *
 * All methods are suspendable to support async operations like external API calls,
 * database queries, or network requests.
 */
public interface UserLifecycleHooks : RealmExtension {

    /**
     * Called before a user is created.
     * Allows validation and transformation of user data.
     *
     * @param email User's email (if provided)
     * @param phone User's phone number (if provided)
     * @param password User's plaintext password
     * @param customAttributes Custom user attributes
     * @param profile User profile information
     * @throws Exception if validation fails
     * @return Transformed data (email, phone, customAttributes, profile)
     */
    public suspend fun beforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData {
        // Default: pass through unchanged
        return UserCreateData(email, phone, customAttributes, profile)
    }

    /**
     * Called before a user is updated.
     * Allows validation and transformation of update data.
     *
     * @param userId ID of the user being updated
     * @param email New email (if provided)
     * @param phone New phone (if provided)
     * @throws Exception if validation fails
     * @return Transformed data (email, phone)
     */
    public suspend fun beforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData {
        // Default: pass through unchanged
        return UserUpdateData(email, phone)
    }

    /**
     * Called before a user profile is updated.
     * Allows validation and transformation of profile data.
     *
     * @param userId ID of the user being updated
     * @param firstName New first name (if provided)
     * @param lastName New last name (if provided)
     * @param address New address (if provided)
     * @param profilePicture New profile picture URL (if provided)
     * @throws Exception if validation fails
     * @return Transformed profile data
     */
    public suspend fun beforeProfileUpdate(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ): UserProfileUpdateData {
        // Default: pass through unchanged
        return UserProfileUpdateData(firstName, lastName, address, profilePicture)
    }

    /**
     * Called before custom attributes are set or updated.
     * Allows validation and sanitization of attributes.
     *
     * @param userId ID of the user
     * @param customAttributes Attributes to set
     * @throws Exception if validation fails
     * @return Sanitized attributes
     */
    public suspend fun beforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> {
        // Default: pass through unchanged
        return customAttributes
    }

    /**
     * Called before a login attempt.
     * Allows checking account status (e.g., lockout) and transforming the identifier.
     *
     * @param identifier User's email or phone
     * @throws Exception if login should be prevented (e.g., account locked)
     * @return Transformed identifier
     */
    public suspend fun beforeLogin(identifier: String): String {
        // Default: pass through unchanged
        return identifier
    }

    /**
     * Called after a failed login attempt.
     * Allows tracking failures for security purposes (e.g., account lockout).
     *
     * @param identifier User's email or phone that failed to authenticate
     */
    public suspend fun afterLoginFailure(identifier: String) {
        // Default: do nothing
    }
}

/**
 * Data class for transformed user creation data.
 */
public data class UserCreateData(
    val email: String?,
    val phone: String?,
    val customAttributes: Map<String, String>?,
    val profile: UserProfile?
)

/**
 * Data class for transformed user update data.
 */
public data class UserUpdateData(
    val email: String?,
    val phone: String?
)

/**
 * Data class for transformed user profile update data.
 */
public data class UserProfileUpdateData(
    val firstName: String?,
    val lastName: String?,
    val address: String?,
    val profilePicture: String?
)
