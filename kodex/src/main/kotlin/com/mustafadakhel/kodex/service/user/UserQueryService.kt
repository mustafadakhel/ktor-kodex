package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import java.util.UUID

/**
 * Query service for user-related read operations (CQRS Query Side).
 *
 * This service handles all user data retrieval operations including:
 * - Basic user queries
 * - Full user with relationships (roles, profile, custom attributes)
 * - Profile queries
 * - Custom attributes queries
 *
 * Query services are optimized for read performance and can be scaled
 * independently from command services. Future optimizations may include:
 * - Caching strategies
 * - Read replicas
 * - Denormalized read models
 *
 * All methods in this service are read-only and have no side effects
 * (no events, no hooks, no state mutations).
 */
public interface UserQueryService {

    // ========== Basic User Queries ==========

    /**
     * Retrieves all users in the system.
     *
     * @return List of all users
     */
    public fun getAllUsers(): List<User>

    /**
     * Retrieves all users with complete data (roles, profiles, custom attributes).
     * Uses eager loading to avoid N+1 query problem.
     *
     * Performance: Fetches all related data in ≤5 queries regardless of user count,
     * compared to 1 + 3N queries with naive approach (N users).
     *
     * @return List of all users with complete data
     */
    public fun getAllFullUsers(): List<FullUser>

    /**
     * Retrieves a user by ID.
     *
     * @param userId The user ID to lookup
     * @return User entity
     * @throws UserNotFound if user doesn't exist
     */
    public fun getUser(userId: UUID): User

    /**
     * Retrieves a user by ID, returning null if not found.
     *
     * @param userId The user ID to lookup
     * @return User entity or null
     */
    public fun getUserOrNull(userId: UUID): User?

    /**
     * Retrieves a user by email address.
     *
     * @param email The email address to lookup
     * @return User entity
     * @throws UserNotFound if user doesn't exist
     */
    public fun getUserByEmail(email: String): User

    /**
     * Retrieves a user by phone number.
     *
     * @param phone The phone number to lookup
     * @return User entity
     * @throws UserNotFound if user doesn't exist
     */
    public fun getUserByPhone(phone: String): User

    // ========== Full User Queries (with relationships) ==========

    /**
     * Retrieves a complete user with all relationships loaded.
     * Includes roles, profile, and custom attributes.
     *
     * @param userId The user ID to lookup
     * @return FullUser entity
     * @throws UserNotFound if user doesn't exist
     */
    public fun getFullUser(userId: UUID): FullUser

    /**
     * Retrieves a complete user with all relationships, returning null if not found.
     *
     * @param userId The user ID to lookup
     * @return FullUser entity or null
     */
    public fun getFullUserOrNull(userId: UUID): FullUser?

    // ========== Profile Queries ==========

    /**
     * Retrieves a user's profile data.
     *
     * @param userId The user ID whose profile to retrieve
     * @return UserProfile entity
     * @throws ProfileNotFound if profile doesn't exist
     */
    public fun getUserProfile(userId: UUID): UserProfile

    /**
     * Retrieves a user's profile data, returning null if not found.
     *
     * @param userId The user ID whose profile to retrieve
     * @return UserProfile entity or null
     */
    public fun getUserProfileOrNull(userId: UUID): UserProfile?

    // ========== Custom Attributes Queries ==========

    /**
     * Retrieves all custom attributes for a user.
     *
     * @param userId The user ID whose attributes to retrieve
     * @return Map of custom attribute key-value pairs
     */
    public fun getCustomAttributes(userId: UUID): Map<String, String>
}
