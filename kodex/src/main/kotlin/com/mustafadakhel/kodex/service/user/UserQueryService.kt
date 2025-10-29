package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import java.util.UUID

/** Handles user read operations. */
public interface UserQueryService {

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
}
