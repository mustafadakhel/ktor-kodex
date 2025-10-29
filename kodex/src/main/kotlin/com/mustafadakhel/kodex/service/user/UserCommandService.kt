package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateResult

/**
 * Command service for user-related write operations (CQRS Command Side).
 *
 * This service handles all user state mutations including:
 * - User creation with validation hooks
 * - User updates via UpdateCommand pattern
 *
 * Command services focus on business logic, consistency, and side effects:
 * - Execute lifecycle hooks (beforeUserCreate, beforeUserUpdate, etc.)
 * - Validate input data
 * - Publish domain events for audit logging
 * - Track changes for transparency
 *
 * All methods in this service are write operations with side effects
 * (events published, hooks executed, state mutated).
 */
public interface UserCommandService {

    /**
     * Creates a new user with the specified credentials and attributes.
     *
     * This operation:
     * 1. Executes beforeUserCreate hooks for validation/transformation
     * 2. Hashes the password using the configured hashing algorithm
     * 3. Creates the user in the repository
     * 4. Publishes a UserEvent.Created event for audit logging
     *
     * The user is automatically assigned to the realm plus any additional roles specified.
     *
     * @param email User's email address (optional if phone provided)
     * @param phone User's phone number (optional if email provided)
     * @param password Plain-text password (will be hashed)
     * @param roleNames Additional role names to assign (realm role added automatically)
     * @param customAttributes Optional custom metadata key-value pairs
     * @param profile Optional user profile data (name, address, etc.)
     * @return Created User entity
     * @throws EmailAlreadyExists if email is already registered
     * @throws PhoneAlreadyExists if phone is already registered
     * @throws RoleNotFound if any specified role doesn't exist
     * @throws Validation if hooks reject the input
     */
    public suspend fun createUser(
        email: String?,
        phone: String? = null,
        password: String,
        roleNames: List<String> = emptyList(),
        customAttributes: Map<String, String>? = null,
        profile: UserProfile? = null
    ): User?

    /**
     * Updates a user using the modern UpdateCommand pattern.
     *
     * This operation:
     * 1. Fetches current user state
     * 2. Executes appropriate hooks (beforeUserUpdate, beforeProfileUpdate, etc.)
     * 3. Applies updates via UpdateCommandProcessor
     * 4. Tracks changes with old→new values
     * 5. Publishes UserEvent.Updated with change metadata
     *
     * Supported commands:
     * - UpdateUserFields: Update email/phone
     * - UpdateProfileFields: Update profile data
     * - UpdateAttributes: Update custom attributes
     * - UpdateUserBatch: Atomic batch update of multiple fields
     *
     * @param command The update command to execute
     * @return UpdateResult with success/failure details and change tracking
     * @throws UserNotFound if user doesn't exist
     * @throws EmailAlreadyExists if new email is already taken
     * @throws PhoneAlreadyExists if new phone is already taken
     * @throws Validation if hooks reject the update
     */
    public suspend fun updateUser(command: UpdateCommand): UpdateResult
}
