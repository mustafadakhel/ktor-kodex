package com.mustafadakhel.kodex.service.role

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Default implementation of RoleService.
 *
 * This implementation handles role queries and user role assignments.
 * It publishes events for role updates to support audit logging.
 */
internal class DefaultRoleService(
    private val userRepository: UserRepository,
    private val eventBus: EventBus,
    private val realm: Realm
) : RoleService {

    override fun getSeededRoles(): List<String> {
        return userRepository.getAllRoles().map { it.name }
    }

    override suspend fun updateUserRoles(userId: UUID, roleNames: List<String>) {
        val timestamp = Clock.System.now()

        // Verify user exists
        userRepository.findById(userId) ?: throw KodexThrowable.UserNotFound("User with id $userId not found")

        // Get current roles for audit
        val currentRoles = userRepository.findRoles(userId).map { it.name }

        // Update roles
        val result = userRepository.updateRolesForUser(userId, roleNames)

        when (result) {
            is UserRepository.UpdateRolesResult.Success -> {
                // Publish event
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
}
