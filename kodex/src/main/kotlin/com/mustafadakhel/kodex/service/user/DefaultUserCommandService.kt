package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.update.UpdateResult
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import java.util.UUID

/**
 * Default implementation of UserCommandService.
 *
 * This implementation orchestrates complex user creation and update workflows
 * including hook execution, validation, event publishing, and change tracking.
 */
internal class DefaultUserCommandService(
    private val userRepository: UserRepository,
    private val hashingService: HashingService,
    private val hookExecutor: HookExecutor,
    private val eventBus: EventBus,
    private val updateCommandProcessor: UpdateCommandProcessor,
    private val timeZone: TimeZone,
    private val realm: Realm
) : UserCommandService {

    override suspend fun createUser(
        email: String?,
        phone: String?,
        password: String,
        roleNames: List<String>,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): User? {
        // Execute beforeUserCreate hooks (validation, transformation)
        val transformed = hookExecutor.executeBeforeUserCreate(
            email, phone, password, customAttributes, profile
        )

        val result = userRepository.create(
            email = transformed.email,
            phone = transformed.phone,
            hashedPassword = hashingService.hash(password),
            roleNames = (listOf(realm.owner) + roleNames).distinct(),
            currentTime = getCurrentLocalDateTime(timeZone),
            customAttributes = transformed.customAttributes,
            profile = transformed.profile
        )
        val user = result.userOrThrow().toUser()

        // Publish event
        kotlinx.coroutines.runBlocking {
            eventBus.publish(
                UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = Clock.System.now(),
                    realmId = realm.owner,
                    userId = user.id,
                    email = email,
                    phone = phone
                )
            )
        }

        return user
    }

    override suspend fun updateUser(command: UpdateCommand): UpdateResult {
        val result = updateCommandProcessor.execute(command)

        // Publish events for successful updates
        when (result) {
            is UpdateResult.Success -> {
                if (result.hasChanges()) {
                    // Build change metadata for event
                    val changeMetadata = buildMap<String, String> {
                        result.changes.changedFields.forEach { (fieldName, change) ->
                            put(fieldName, change.newValue?.toString() ?: "")
                        }
                    }

                    runCatching {
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
            }
            is UpdateResult.Failure -> {
                // Failures don't change state, so no event published
            }
        }

        return result
    }

    private fun UserRepository.CreateUserResult.userOrThrow() = when (this) {
        is UserRepository.CreateUserResult.EmailAlreadyExists ->
            throw KodexThrowable.EmailAlreadyExists()

        is UserRepository.CreateUserResult.InvalidRole -> throw KodexThrowable.RoleNotFound(roleName)
        is UserRepository.CreateUserResult.Success -> user
        is UserRepository.CreateUserResult.PhoneAlreadyExists ->
            throw KodexThrowable.PhoneAlreadyExists()
    }

    private fun com.mustafadakhel.kodex.model.database.UserEntity.toUser() = User(
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
