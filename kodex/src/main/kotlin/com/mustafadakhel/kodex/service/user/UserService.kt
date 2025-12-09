package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateResult
import java.util.UUID

/**
 * Consolidated service for all user-related operations.
 *
 * This service handles user queries, commands, profile management,
 * role assignments, verification, and custom attributes.
 */
public interface UserService {

    public fun getAllUsers(): List<User>
    public fun getAllFullUsers(): List<FullUser>
    public fun getUser(userId: UUID): User
    public fun getUserOrNull(userId: UUID): User?
    public fun getUserByEmail(email: String): User
    public fun getUserByPhone(phone: String): User
    public fun getFullUser(userId: UUID): FullUser
    public fun getFullUserOrNull(userId: UUID): FullUser?
    public fun getUserProfile(userId: UUID): UserProfile
    public fun getUserProfileOrNull(userId: UUID): UserProfile?
    public fun getCustomAttributes(userId: UUID): Map<String, String>

    public suspend fun createUser(
        email: String?,
        phone: String? = null,
        password: String,
        roleNames: List<String> = emptyList(),
        customAttributes: Map<String, String>? = null,
        profile: UserProfile? = null
    ): User?

    public suspend fun updateUser(command: UpdateCommand): UpdateResult

    public suspend fun deleteUser(userId: UUID): Boolean

    public fun getSeededRoles(): List<String>
    public suspend fun updateUserRoles(userId: UUID, roleNames: List<String>)
}
