package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.model.database.toUserProfile
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable
import java.util.UUID

/**
 * Default implementation of UserQueryService.
 *
 * This implementation delegates to UserRepository for all read operations.
 * It performs entity-to-model mapping and handles not-found scenarios.
 */
internal class DefaultUserQueryService(
    private val userRepository: UserRepository
) : UserQueryService {

    override fun getAllUsers(): List<User> {
        return userRepository.getAll().map { userEntity ->
            userEntity.toUser()
        }
    }

    override fun getAllFullUsers(): List<FullUser> {
        return userRepository.getAllFull().map { fullUserEntity ->
            fullUserEntity.toFullUser()
        }
    }

    override fun getUser(userId: UUID): User {
        return userRepository.findById(userId)?.toUser()
            ?: throw KodexThrowable.UserNotFound("User with id $userId not found")
    }

    override fun getUserOrNull(userId: UUID): User? {
        val user = userRepository.findById(userId)
        return user?.toUser()
    }

    override fun getUserByEmail(email: String): User {
        return userRepository.findByEmail(email)?.toUser()
            ?: throw KodexThrowable.UserNotFound("User with email $email not found")
    }

    override fun getUserByPhone(phone: String): User {
        return userRepository.findByPhone(phone)?.toUser()
            ?: throw KodexThrowable.UserNotFound("User with phone number $phone not found")
    }

    override fun getFullUser(userId: UUID): FullUser {
        return getFullUserOrNull(userId) ?: throw KodexThrowable.UserNotFound("User with id $userId not found")
    }

    override fun getFullUserOrNull(userId: UUID): FullUser? {
        val user = userRepository.findFullById(userId)
        return user?.toFullUser()
    }

    override fun getUserProfile(userId: UUID): UserProfile {
        return getUserProfileOrNull(userId)
            ?: throw KodexThrowable.ProfileNotFound(userId)
    }

    override fun getUserProfileOrNull(userId: UUID): UserProfile? {
        val profile = userRepository.findProfileByUserId(userId) ?: return null
        return profile.toUserProfile()
    }

    override fun getCustomAttributes(userId: UUID): Map<String, String> {
        return userRepository.findCustomAttributesByUserId(userId)
    }

    private fun UserEntity.toUser() = User(
        id = id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isVerified = isVerified,
        email = email,
        phoneNumber = phoneNumber,
        lastLoggedIn = lastLoggedIn,
        status = status
    )
}
