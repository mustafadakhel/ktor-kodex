package com.mustafadakhel.kodex.update

import java.util.UUID

/** Base interface for user update commands. */
public sealed interface UpdateCommand {
    public val userId: UUID
}

public data class UpdateUserFields(
    override val userId: UUID,
    val fields: UserFieldUpdates
) : UpdateCommand

public data class UpdateProfileFields(
    override val userId: UUID,
    val fields: ProfileFieldUpdates
) : UpdateCommand

public data class UpdateAttributes(
    override val userId: UUID,
    val changes: AttributeChanges
) : UpdateCommand

/** Performs multiple updates atomically. */
public data class UpdateUserBatch(
    override val userId: UUID,
    val userFields: UserFieldUpdates? = null,
    val profileFields: ProfileFieldUpdates? = null,
    val attributeChanges: AttributeChanges? = null
) : UpdateCommand {
    public fun hasChanges(): Boolean =
        (userFields?.hasChanges() == true) ||
        (profileFields?.hasChanges() == true) ||
        (attributeChanges?.hasChanges() == true)
}
