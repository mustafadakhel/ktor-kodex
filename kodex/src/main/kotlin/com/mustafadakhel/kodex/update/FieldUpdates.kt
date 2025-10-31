package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.UserStatus

/**
 * Specification for updating user fields.
 * Only fields with non-NoChange updates will be modified.
 */
public data class UserFieldUpdates(
    val email: FieldUpdate<String> = FieldUpdate.NoChange(),
    val phone: FieldUpdate<String> = FieldUpdate.NoChange(),
    val status: FieldUpdate<UserStatus> = FieldUpdate.NoChange()
) {
    /**
     * Returns true if any field has a change.
     */
    public fun hasChanges(): Boolean =
        email.hasChange() || phone.hasChange() || status.hasChange()

    /**
     * Returns a list of field names that have changes.
     */
    public fun changedFields(): List<String> = buildList {
        if (email.hasChange()) add("email")
        if (phone.hasChange()) add("phone")
        if (status.hasChange()) add("status")
    }
}

/**
 * Specification for updating user profile fields.
 * Only fields with non-NoChange updates will be modified.
 */
public data class ProfileFieldUpdates(
    val firstName: FieldUpdate<String> = FieldUpdate.NoChange(),
    val lastName: FieldUpdate<String> = FieldUpdate.NoChange(),
    val address: FieldUpdate<String> = FieldUpdate.NoChange(),
    val profilePicture: FieldUpdate<String> = FieldUpdate.NoChange()
) {
    /**
     * Returns true if any field has a change.
     */
    public fun hasChanges(): Boolean =
        firstName.hasChange() || lastName.hasChange() ||
        address.hasChange() || profilePicture.hasChange()

    /**
     * Returns a list of field names that have changes.
     */
    public fun changedFields(): List<String> = buildList {
        if (firstName.hasChange()) add("firstName")
        if (lastName.hasChange()) add("lastName")
        if (address.hasChange()) add("address")
        if (profilePicture.hasChange()) add("profilePicture")
    }
}

/**
 * Represents a change to custom attributes.
 */
public sealed interface AttributeChange {
    /**
     * Set a specific attribute to a value.
     */
    public data class Set(val key: String, val value: String) : AttributeChange

    /**
     * Remove a specific attribute.
     */
    public data class Remove(val key: String) : AttributeChange

    /**
     * Replace all attributes with a new set.
     */
    public data class ReplaceAll(val attributes: Map<String, String>) : AttributeChange
}

/**
 * Specification for updating custom attributes.
 */
public data class AttributeChanges(
    val changes: List<AttributeChange>
) {
    /**
     * Returns true if there are any changes.
     */
    public fun hasChanges(): Boolean = changes.isNotEmpty()

    /**
     * Returns all keys that will be affected by these changes.
     */
    public fun affectedKeys(): Set<String> = changes.flatMapTo(mutableSetOf()) { change ->
        when (change) {
            is AttributeChange.Set -> listOf(change.key)
            is AttributeChange.Remove -> listOf(change.key)
            is AttributeChange.ReplaceAll -> change.attributes.keys
        }
    }
}
