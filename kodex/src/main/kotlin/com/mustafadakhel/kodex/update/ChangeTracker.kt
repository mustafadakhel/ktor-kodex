package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.model.database.toUserProfile
import com.mustafadakhel.kodex.util.CurrentKotlinInstant

/** Detects and tracks changes to user data. */
internal class ChangeTracker {

    fun detectUserFieldChanges(
        current: FullUser,
        updates: UserFieldUpdates
    ): Map<String, FieldChange> = buildMap {
        when (val emailUpdate = updates.email) {
            is FieldUpdate.SetValue -> {
                if (current.email != emailUpdate.value) {
                    put("email", FieldChange("email", current.email, emailUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (current.email != null) {
                    put("email", FieldChange("email", current.email, null))
                }
            }
            is FieldUpdate.NoChange -> {} // No change
        }

        when (val phoneUpdate = updates.phone) {
            is FieldUpdate.SetValue -> {
                if (current.phoneNumber != phoneUpdate.value) {
                    put("phoneNumber", FieldChange("phoneNumber", current.phoneNumber, phoneUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (current.phoneNumber != null) {
                    put("phoneNumber", FieldChange("phoneNumber", current.phoneNumber, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val statusUpdate = updates.status) {
            is FieldUpdate.SetValue -> {
                if (current.status != statusUpdate.value) {
                    put("status", FieldChange("status", current.status, statusUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                // status is non-nullable, ignore
            }
            is FieldUpdate.NoChange -> {}
        }
    }

    fun detectProfileFieldChanges(
        currentProfile: UserProfile?,
        updates: ProfileFieldUpdates
    ): Map<String, FieldChange> = buildMap {
        when (val firstNameUpdate = updates.firstName) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.firstName != firstNameUpdate.value) {
                    put("profile.firstName", FieldChange("profile.firstName", currentProfile?.firstName, firstNameUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.firstName != null) {
                    put("profile.firstName", FieldChange("profile.firstName", currentProfile.firstName, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val lastNameUpdate = updates.lastName) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.lastName != lastNameUpdate.value) {
                    put("profile.lastName", FieldChange("profile.lastName", currentProfile?.lastName, lastNameUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.lastName != null) {
                    put("profile.lastName", FieldChange("profile.lastName", currentProfile.lastName, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val addressUpdate = updates.address) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.address != addressUpdate.value) {
                    put("profile.address", FieldChange("profile.address", currentProfile?.address, addressUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.address != null) {
                    put("profile.address", FieldChange("profile.address", currentProfile.address, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val pictureUpdate = updates.profilePicture) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.profilePicture != pictureUpdate.value) {
                    put("profile.profilePicture", FieldChange("profile.profilePicture", currentProfile?.profilePicture, pictureUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.profilePicture != null) {
                    put("profile.profilePicture", FieldChange("profile.profilePicture", currentProfile.profilePicture, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }
    }

    fun detectAttributeChanges(
        currentAttributes: Map<String, String>,
        changes: AttributeChanges
    ): Map<String, FieldChange> = buildMap {
        changes.changes.forEach { change ->
            when (change) {
                is AttributeChange.Set -> {
                    val currentValue = currentAttributes[change.key]
                    if (currentValue != change.value) {
                        put("customAttributes.${change.key}",
                            FieldChange("customAttributes.${change.key}", currentValue, change.value))
                    }
                }
                is AttributeChange.Remove -> {
                    val currentValue = currentAttributes[change.key]
                    if (currentValue != null) {
                        put("customAttributes.${change.key}",
                            FieldChange("customAttributes.${change.key}", currentValue, null))
                    }
                }
                is AttributeChange.ReplaceAll -> {
                    // For ReplaceAll, we need to track all changes
                    // Keys that exist in current but not in new are removed
                    currentAttributes.forEach { (key, value) ->
                        if (key !in change.attributes) {
                            put("customAttributes.$key",
                                FieldChange("customAttributes.$key", value, null))
                        }
                    }
                    // Keys in new that differ from current
                    change.attributes.forEach { (key, value) ->
                        val currentValue = currentAttributes[key]
                        if (currentValue != value) {
                            put("customAttributes.$key",
                                FieldChange("customAttributes.$key", currentValue, value))
                        }
                    }
                }
            }
        }
    }

    fun detectBatchChanges(
        current: FullUser,
        batch: UpdateUserBatch
    ): ChangeSet {
        val timestamp = CurrentKotlinInstant
        val allChanges = mutableMapOf<String, FieldChange>()

        batch.userFields?.let { updates ->
            allChanges.putAll(detectUserFieldChanges(current, updates))
        }

        batch.profileFields?.let { updates ->
            allChanges.putAll(detectProfileFieldChanges(current.profile, updates))
        }

        batch.attributeChanges?.let { changes ->
            allChanges.putAll(detectAttributeChanges(current.customAttributes ?: emptyMap(), changes))
        }

        return ChangeSet(
            timestamp = timestamp,
            changedFields = allChanges
        )
    }

    fun detectChanges(
        current: FullUserEntity,
        command: UpdateCommand
    ): ChangeSet {
        val timestamp = CurrentKotlinInstant
        return when (command) {
            is UpdateUserFields -> ChangeSet(
                timestamp = timestamp,
                changedFields = detectUserFieldChanges(current.toFullUser(), command.fields)
            )
            is UpdateProfileFields -> ChangeSet(
                timestamp = timestamp,
                changedFields = detectProfileFieldChanges(current.profile?.toUserProfile(), command.fields)
            )
            is UpdateAttributes -> ChangeSet(
                timestamp = timestamp,
                changedFields = detectAttributeChanges(current.customAttributes ?: emptyMap(), command.changes)
            )
            is UpdateUserBatch -> detectBatchChanges(current.toFullUser(), command)
        }
    }
}
