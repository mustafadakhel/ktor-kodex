package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenPair
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
     * Only non-null parameters are persisted.
     */
    public fun updateUserProfileById(
        userId: UUID,
        firstName: String? = null,
        lastName: String? = null,
        address: String? = null,
        profilePicture: String? = null,
    )

    /**
     * Updates user fields for the given [userId].
     * Only non-null parameters are persisted.
     */
    public fun updateUserById(
        userId: UUID,
        email: String? = null,
        phone: String? = null
    )

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
    public fun createUser(
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

    /** replaces all custom attributes for the user with [userId]. */
    public fun replaceAllCustomAttributes(
        userId: UUID,
        customAttributes: Map<String, String>
    )

    /** Updates custom attributes for the user with [userId]. */
    public fun updateCustomAttributes(
        userId: UUID,
        customAttributes: Map<String, String>
    )

    /** Sets verification status for the user. */
    public fun setVerified(userId: UUID, verified: Boolean)

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
