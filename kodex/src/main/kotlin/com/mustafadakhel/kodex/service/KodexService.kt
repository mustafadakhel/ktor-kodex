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
    public fun getAllUsers(): List<User>

    /**
     * Returns all users with complete data (roles, profiles, custom attributes).
     * Uses eager loading to avoid N+1 query problem.
     *
     * Performance: Fetches all related data in ≤5 queries regardless of user count,
     * compared to 1 + 3N queries with naive approach (N users).
     */
    public fun getAllFullUsers(): List<FullUser>

    public fun getUser(userId: UUID): User

    public fun getUserOrNull(userId: UUID): User?

    public fun getUserProfile(userId: UUID): UserProfile

    public fun getUserByEmail(email: String): User

    public fun getUserByPhone(phone: String): User

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

    public fun getUserProfileOrNull(userId: UUID): UserProfile?

    public fun getFullUser(userId: UUID): FullUser

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

    public fun getCustomAttributes(userId: UUID): Map<String, String>

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

    public fun setVerified(userId: UUID, verified: Boolean)

    /**
     * Changes the user's password after verifying the old password.
     *
     * @throws InvalidCredentials if the old password is incorrect
     * @throws UserNotFound if the user doesn't exist
     */
    public suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String)

    /**
     * Resets the user's password without requiring the old password.
     * This is intended for admin use or password recovery flows.
     *
     * @throws UserNotFound if the user doesn't exist
     */
    public suspend fun resetPassword(userId: UUID, newPassword: String)

    public suspend fun tokenByEmail(email: String, password: String): TokenPair

    public suspend fun tokenByPhone(phone: String, password: String): TokenPair

    public suspend fun refresh(userId: UUID, refreshToken: String): TokenPair

    public fun revokeTokens(userId: UUID)

    public fun revokeToken(token: String, delete: Boolean = true)

    public fun verifyAccessToken(token: String): KodexPrincipal?
}
