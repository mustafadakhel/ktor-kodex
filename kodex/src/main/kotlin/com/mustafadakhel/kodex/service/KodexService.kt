package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateResult
import java.util.*

/**
 * Contract for the Kodex module.
 *
 * Implementations provide user management, authentication and token related
 * operations for a specific realm.
 */
public interface KodexService {
    /** Returns all users in the realm. */
    public fun getAllUsers(): List<User>

    /**
     * Returns all users with complete data (roles, profiles, custom attributes).
     * Uses eager loading to avoid N+1 query problem.
     *
     * Performance: Fetches all related data in ≤5 queries regardless of user count,
     * compared to 1 + 3N queries with naive approach (N users).
     */
    public fun getAllFullUsers(): List<FullUser>

    /** Returns the user with the supplied [userId] or throws if absent. */
    public fun getUser(userId: UUID): User

    /** Returns the user with [userId] or `null` if not found. */
    public fun getUserOrNull(userId: UUID): User?

    /** Retrieves the profile data for [userId]. */
    public fun getUserProfile(userId: UUID): UserProfile

    /** Finds a user by [email]. */
    public fun getUserByEmail(email: String): User

    /** Finds a user by [phone]. */
    public fun getUserByPhone(phone: String): User

    /**
     * Updates profile fields for the given [userId].
     *
     * @deprecated Use [updateUser] with [UpdateProfileFields] for better type safety and change tracking.
     * Migration example:
     * ```kotlin
     * // Old
     * updateUserProfileById(userId, firstName = "John", lastName = "Doe")
     *
     * // New
     * updateUser(UpdateProfileFields(userId, ProfileFieldUpdates(
     *     firstName = "John".asUpdate(),
     *     lastName = "Doe".asUpdate()
     * )))
     * ```
     */
    @Deprecated(
        message = "Use updateUser() with UpdateProfileFields for better type safety and change tracking",
        replaceWith = ReplaceWith("updateUser(UpdateProfileFields(userId, ProfileFieldUpdates(...)))"),
        level = DeprecationLevel.WARNING
    )
    public suspend fun updateUserProfileById(
        userId: UUID,
        firstName: String? = null,
        lastName: String? = null,
        address: String? = null,
        profilePicture: String? = null,
    )

    /**
     * Updates user fields for the given [userId].
     *
     * @deprecated Use [updateUser] with [UpdateUserFields] for better type safety and change tracking.
     * Migration example:
     * ```kotlin
     * // Old
     * updateUserById(userId, email = "new@example.com")
     *
     * // New
     * updateUser(UpdateUserFields(userId, UserFieldUpdates(
     *     email = "new@example.com".asUpdate()
     * )))
     * ```
     */
    @Deprecated(
        message = "Use updateUser() with UpdateUserFields for better type safety and change tracking",
        replaceWith = ReplaceWith("updateUser(UpdateUserFields(userId, UserFieldUpdates(...)))"),
        level = DeprecationLevel.WARNING
    )
    public suspend fun updateUserById(
        userId: UUID,
        email: String? = null,
        phone: String? = null
    )

    /**
     * Executes an update command using the modern update system.
     *
     * This method provides:
     * - **Precise change tracking**: Know exactly what changed (old → new values)
     * - **Type-safe field updates**: Explicit three-state semantics per field
     * - **Validation integration**: Hooks can validate and transform values
     * - **Atomic batch updates**: Multiple fields updated in single transaction
     *
     * Field update semantics:
     * - `FieldUpdate.NoChange`: Don't modify the field (default)
     * - `FieldUpdate.SetValue(value)`: Set field to specific value
     * - `FieldUpdate.ClearValue`: Set nullable field to null
     *
     * Example usage:
     * ```kotlin
     * // Update single field
     * val result = updateUser(UpdateUserFields(userId, UserFieldUpdates(
     *     email = "new@example.com".asUpdate()
     * )))
     *
     * // Batch update (atomic)
     * val result = updateUser(UpdateUserBatch(
     *     userId = userId,
     *     userFields = UserFieldUpdates(email = "new@example.com".asUpdate()),
     *     profileFields = ProfileFieldUpdates(firstName = "John".asUpdate())
     * ))
     *
     * // Handle result
     * when (result) {
     *     is UpdateResult.Success -> println("Changed: ${result.changes.changedFieldNames()}")
     *     is UpdateResult.Failure.ValidationFailed -> println("Errors: ${result.errors}")
     *     is UpdateResult.Failure.NotFound -> println("User not found")
     * }
     * ```
     *
     * @param command The update command to execute
     * @return UpdateResult containing the updated user, changes, or failure details
     */
    public suspend fun updateUser(command: UpdateCommand): UpdateResult

    /** Returns a profile if the user exists. */
    public fun getUserProfileOrNull(userId: UUID): UserProfile?

    /** Returns a full user with profile and credentials. */
    public fun getFullUser(userId: UUID): FullUser

    /** Returns full user information or `null` if absent. */
    public fun getFullUserOrNull(userId: UUID): FullUser?

    /**
     * Registers a new user using the supplied [password], [email] and/or [phone]
     * and returns the created [User] or `null` if the user already exists.
     * Optionally, you can provide [customAttributes], [roleNames] and a [profile].
     */
    public suspend fun createUser(
        email: String?,
        phone: String? = null,
        password: String,
        roleNames: List<String> = emptyList(),
        customAttributes: Map<String, String>? = null,
        profile: UserProfile? = null,
    ): User?

    /** Returns all custom attributes for the user with [userId]. */
    public fun getCustomAttributes(
        userId: UUID
    ): Map<String, String>

    /**
     * Replaces all custom attributes for the user with [userId].
     *
     * @deprecated Use [updateUser] with [UpdateAttributes] for better change tracking.
     * Migration example:
     * ```kotlin
     * // Old
     * replaceAllCustomAttributes(userId, mapOf("key1" to "value1"))
     *
     * // New
     * updateUser(UpdateAttributes(userId, AttributeChanges(
     *     listOf(AttributeChange.ReplaceAll(mapOf("key1" to "value1")))
     * )))
     * ```
     */
    @Deprecated(
        message = "Use updateUser() with UpdateAttributes for better change tracking",
        replaceWith = ReplaceWith("updateUser(UpdateAttributes(userId, AttributeChanges(...)))"),
        level = DeprecationLevel.WARNING
    )
    public suspend fun replaceAllCustomAttributes(
        userId: UUID,
        customAttributes: Map<String, String>
    )

    /**
     * Updates custom attributes for the user with [userId].
     *
     * @deprecated Use [updateUser] with [UpdateAttributes] for granular change tracking.
     * Migration example:
     * ```kotlin
     * // Old
     * updateCustomAttributes(userId, mapOf("key1" to "value1"))
     *
     * // New
     * updateUser(UpdateAttributes(userId, AttributeChanges(
     *     listOf(AttributeChange.Set("key1", "value1"))
     * )))
     * ```
     */
    @Deprecated(
        message = "Use updateUser() with UpdateAttributes for granular change tracking",
        replaceWith = ReplaceWith("updateUser(UpdateAttributes(userId, AttributeChanges(...)))"),
        level = DeprecationLevel.WARNING
    )
    public suspend fun updateCustomAttributes(
        userId: UUID,
        customAttributes: Map<String, String>
    )

    /** Returns a list of all roles in the realm. */
    public fun getSeededRoles(): List<String>

    /**
     * Updates the roles assigned to a user.
     *
     * @param userId The user whose roles to update
     * @param roleNames The new list of role names to assign
     * @throws UserNotFound if the user doesn't exist
     * @throws RoleNotFound if any of the specified roles don't exist
     */
    public suspend fun updateUserRoles(userId: UUID, roleNames: List<String>)

    /** Sets verification status for the user. */
    public fun setVerified(userId: UUID, verified: Boolean)

    /**
     * Changes the user's password after verifying the old password.
     *
     * @param userId The user whose password to change
     * @param oldPassword The current password for verification
     * @param newPassword The new password to set
     * @throws InvalidCredentials if the old password is incorrect
     * @throws UserNotFound if the user doesn't exist
     */
    public suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String)

    /**
     * Resets the user's password without requiring the old password.
     * This is intended for admin use or password recovery flows.
     *
     * @param userId The user whose password to reset
     * @param newPassword The new password to set
     * @throws UserNotFound if the user doesn't exist
     */
    public suspend fun resetPassword(userId: UUID, newPassword: String)

    /** Logs a user in via e-mail and password, returning a [TokenPair]. */
    public suspend fun tokenByEmail(email: String, password: String): TokenPair

    /** Logs a user in via phone number and password, returning a [TokenPair]. */
    public suspend fun tokenByPhone(phone: String, password: String): TokenPair

    /** Issues new tokens for [userId] when the [refreshToken] is valid. */
    public suspend fun refresh(
        userId: UUID,
        refreshToken: String
    ): TokenPair

    /** Revokes all tokens belonging to [userId]. */
    public fun revokeTokens(userId: UUID)

    /** Revokes the access [token]. */
    public fun revokeToken(token: String, delete: Boolean = true)

    /**
     * Validates an access [token] and returns the associated [KodexPrincipal]
     * when the token is valid or `null` otherwise.
     */
    public fun verifyAccessToken(
        token: String
    ): KodexPrincipal?
}
