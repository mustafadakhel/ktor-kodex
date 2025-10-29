package com.mustafadakhel.kodex.update

import java.util.UUID

/**
 * Base interface for all user update commands.
 * Commands represent the intent to modify user data.
 */
public sealed interface UpdateCommand {
    /**
     * The ID of the user to update.
     */
    public val userId: UUID
}

/**
 * Command to update specific user fields.
 */
public data class UpdateUserFields(
    override val userId: UUID,
    val fields: UserFieldUpdates
) : UpdateCommand

/**
 * Command to update specific profile fields.
 */
public data class UpdateProfileFields(
    override val userId: UUID,
    val fields: ProfileFieldUpdates
) : UpdateCommand

/**
 * Command to update custom attributes.
 */
public data class UpdateAttributes(
    override val userId: UUID,
    val changes: AttributeChanges
) : UpdateCommand

/**
 * Command to perform multiple updates atomically.
 * All updates succeed or all fail together.
 */
public data class UpdateUserBatch(
    override val userId: UUID,
    val userFields: UserFieldUpdates? = null,
    val profileFields: ProfileFieldUpdates? = null,
    val attributeChanges: AttributeChanges? = null
) : UpdateCommand {
    /**
     * Returns true if the batch contains any actual changes.
     */
    public fun hasChanges(): Boolean =
        (userFields?.hasChanges() == true) ||
        (profileFields?.hasChanges() == true) ||
        (attributeChanges?.hasChanges() == true)
}
