package com.mustafadakhel.kodex.service.role

import java.util.UUID

/** Handles role management and assignment. */
public interface RoleService {
    /** Gets all seeded roles in the system. */
    public fun getSeededRoles(): List<String>

    /** Updates roles assigned to a user. */
    public suspend fun updateUserRoles(userId: UUID, roleNames: List<String>)
}
