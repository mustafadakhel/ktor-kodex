package com.mustafadakhel.kodex.service.role

import java.util.UUID

/**
 * Service responsible for role management and assignment.
 *
 * This service handles role-related operations including querying available
 * roles and assigning roles to users. Roles are used for authorization and
 * access control.
 *
 * Current capabilities:
 * - Query seeded roles
 * - Update user role assignments
 *
 * Future expansion:
 * - Role creation/deletion
 * - Role hierarchies
 * - Permission management (RBAC)
 * - Role-based access policies
 */
public interface RoleService {
    /**
     * Retrieves all seeded roles in the system.
     *
     * Seeded roles are predefined roles configured during realm setup.
     * These typically include roles like "Admin", "User", "Moderator", etc.
     *
     * @return List of seeded role names
     */
    public fun getSeededRoles(): List<String>

    /**
     * Updates the roles assigned to a user.
     *
     * This operation replaces all existing roles for the user with the
     * provided list of roles. It publishes a UserEvent.RolesUpdated event
     * for audit logging.
     *
     * @param userId The user whose roles to update
     * @param roleNames The new list of role names to assign
     * @throws UserNotFound if the user doesn't exist
     * @throws RoleNotFound if any of the specified roles don't exist
     */
    public suspend fun updateUserRoles(userId: UUID, roleNames: List<String>)
}
