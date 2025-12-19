package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.model.database.toUserProfile
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.update.UpdateResult
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now as nowLocal
import kotlinx.datetime.TimeZone
import java.util.UUID

/**
 * Default implementation of UserService.
 *
 * Consolidates user queries, commands, profile management, role assignments,
 * verification, and custom attributes into a single cohesive service.
 */
internal class DefaultUserService(
    private val userRepository: UserRepository,
    private val hashingService: HashingService,
    private val hookExecutor: HookExecutor,
    private val eventBus: EventBus,
    private val updateCommandProcessor: UpdateCommandProcessor,
    private val timeZone: TimeZone,
    private val realm: Realm
) : UserService {

    override fun getAllUsers(): List<User> {
        return userRepository.getAll().map { it.toUser() }
    }

    override fun getAllFullUsers(): List<FullUser> {
        return userRepository.getAllFull().map { it.toFullUser() }
    }

    override fun getUser(userId: UUID): User {
        return userRepository.findById(userId)?.toUser()
            ?: throw KodexThrowable.UserNotFound("User with id $userId not found")
    }

    override fun getUserOrNull(userId: UUID): User? {
        return userRepository.findById(userId)?.toUser()
    }

    override fun getUserByEmail(email: String): User {
        return userRepository.findByEmail(email, realm.name)?.toUser()
            ?: throw KodexThrowable.UserNotFound("User with email $email not found")
    }

    override fun getUserByPhone(phone: String): User {
        return userRepository.findByPhone(phone, realm.name)?.toUser()
            ?: throw KodexThrowable.UserNotFound("User with phone number $phone not found")
    }

    override fun getFullUser(userId: UUID): FullUser {
        return getFullUserOrNull(userId)
            ?: throw KodexThrowable.UserNotFound("User with id $userId not found")
    }

    override fun getFullUserOrNull(userId: UUID): FullUser? {
        return userRepository.findFullById(userId)?.toFullUser()
    }

    override fun getUserProfile(userId: UUID): UserProfile {
        return getUserProfileOrNull(userId)
            ?: throw KodexThrowable.ProfileNotFound(userId)
    }

    override fun getUserProfileOrNull(userId: UUID): UserProfile? {
        return userRepository.findProfileByUserId(userId)?.toUserProfile()
    }

    override fun getCustomAttributes(userId: UUID): Map<String, String> {
        return userRepository.findCustomAttributesByUserId(userId)
    }

    override suspend fun createUser(
        email: String?,
        phone: String?,
        password: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): User? {
        val timestamp = CurrentKotlinInstant

        return try {
            val transformed = hookExecutor.executeBeforeUserCreate(
                email, phone, password, customAttributes, profile
            )

            val result = userRepository.create(
                email = transformed.email,
                phone = transformed.phone,
                hashedPassword = hashingService.hash(password),
                roleNames = (listOf(realm.owner) + roleNames).distinct(),
                currentTime = nowLocal(timeZone),
                customAttributes = transformed.customAttributes,
                profile = transformed.profile,
                realmId = realm.name
            )
            val user = result.userOrThrow().toUser()

            eventBus.publish(
                UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = timestamp,
                    realmId = realm.owner,
                    userId = user.id,
                    email = email,
                    phone = phone
                )
            )

            user
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun updateUser(command: UpdateCommand): UpdateResult {
        val result = updateCommandProcessor.execute(command)

        when (result) {
            is UpdateResult.Success -> {
                if (result.hasChanges()) {
                    val changeMetadata = buildMap<String, String> {
                        result.changes.changedFields.forEach { (fieldName, change) ->
                            put(fieldName, change.newValue?.toString() ?: "")
                        }
                    }

                    eventBus.publish(
                        UserEvent.Updated(
                            eventId = UUID.randomUUID(),
                            timestamp = result.changes.timestamp,
                            realmId = realm.owner,
                            userId = command.userId,
                            actorId = command.userId,
                            changes = changeMetadata
                        )
                    )
                }
            }
            is UpdateResult.Failure -> {
                // Failures don't change state, so no event published
            }
        }

        return result
    }

    override suspend fun deleteUser(userId: UUID): Boolean {
        // Execute beforeUserDelete hooks (extensions can perform cleanup)
        hookExecutor.executeBeforeUserDelete(userId)

        // Delete user from database
        val result = userRepository.deleteUser(userId)

        if (result is UserRepository.DeleteResult.Success) {
            eventBus.publish(
                UserEvent.Deleted(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm.owner,
                    userId = userId,
                    actorId = userId
                )
            )
            return true
        }

        return false
    }

    override fun getSeededRoles(): List<String> {
        return userRepository.getAllRoles().map { it.name }
    }

    override suspend fun updateUserRoles(userId: UUID, roleNames: List<String>) {
        val timestamp = CurrentKotlinInstant

        userRepository.findById(userId)
            ?: throw KodexThrowable.UserNotFound("User with id $userId not found")

        val currentRoles = userRepository.findRoles(userId).map { it.name }

        val result = userRepository.updateRolesForUser(userId, roleNames)

        when (result) {
            is UserRepository.UpdateRolesResult.Success -> {
                eventBus.publish(
                    UserEvent.RolesUpdated(
                        eventId = UUID.randomUUID(),
                        timestamp = timestamp,
                        realmId = realm.owner,
                        userId = userId,
                        actorType = "ADMIN",
                        previousRoles = currentRoles.toSet(),
                        newRoles = roleNames.toSet()
                    )
                )
            }
            is UserRepository.UpdateRolesResult.InvalidRole -> {
                throw KodexThrowable.RoleNotFound(result.roleName)
            }
        }
    }

    private fun UserRepository.CreateUserResult.userOrThrow() = when (this) {
        is UserRepository.CreateUserResult.EmailAlreadyExists ->
            throw KodexThrowable.EmailAlreadyExists()
        is UserRepository.CreateUserResult.InvalidRole ->
            throw KodexThrowable.RoleNotFound(roleName)
        is UserRepository.CreateUserResult.Success -> user
        is UserRepository.CreateUserResult.PhoneAlreadyExists ->
            throw KodexThrowable.PhoneAlreadyExists()
    }

    private fun UserEntity.toUser() = User(
        id = id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        email = email,
        phoneNumber = phoneNumber,
        lastLoggedIn = lastLoggedIn,
        status = status
    )
}
