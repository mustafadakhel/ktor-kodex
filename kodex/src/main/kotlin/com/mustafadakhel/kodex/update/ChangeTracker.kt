package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.model.database.toUserProfile
import com.mustafadakhel.kodex.util.CurrentKotlinInstant

internal class ChangeTracker {

    fun detectUserFieldChanges(
        current: FullUser,
        updates: UserFieldUpdates
    ): Map<String, FieldChange> = buildMap {
        when (val emailUpdate = updates.email) {
            is FieldUpdate.SetValue -> {
                if (current.email != emailUpdate.value) {
                    put(UserField.EMAIL.key, FieldChange(UserField.EMAIL.key, current.email, emailUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (current.email != null) {
                    put(UserField.EMAIL.key, FieldChange(UserField.EMAIL.key, current.email, null))
                }
            }
            is FieldUpdate.NoChange -> {} // No change
        }

        when (val phoneUpdate = updates.phone) {
            is FieldUpdate.SetValue -> {
                if (current.phoneNumber != phoneUpdate.value) {
                    put(UserField.PHONE.key, FieldChange(UserField.PHONE.key, current.phoneNumber, phoneUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (current.phoneNumber != null) {
                    put(UserField.PHONE.key, FieldChange(UserField.PHONE.key, current.phoneNumber, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val statusUpdate = updates.status) {
            is FieldUpdate.SetValue -> {
                if (current.status != statusUpdate.value) {
                    put(UserField.STATUS.key, FieldChange(UserField.STATUS.key, current.status, statusUpdate.value))
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
                    put(UserField.PROFILE_FIRST_NAME.key, FieldChange(UserField.PROFILE_FIRST_NAME.key, currentProfile?.firstName, firstNameUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.firstName != null) {
                    put(UserField.PROFILE_FIRST_NAME.key, FieldChange(UserField.PROFILE_FIRST_NAME.key, currentProfile.firstName, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val lastNameUpdate = updates.lastName) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.lastName != lastNameUpdate.value) {
                    put(UserField.PROFILE_LAST_NAME.key, FieldChange(UserField.PROFILE_LAST_NAME.key, currentProfile?.lastName, lastNameUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.lastName != null) {
                    put(UserField.PROFILE_LAST_NAME.key, FieldChange(UserField.PROFILE_LAST_NAME.key, currentProfile.lastName, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val addressUpdate = updates.address) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.address != addressUpdate.value) {
                    put(UserField.PROFILE_ADDRESS.key, FieldChange(UserField.PROFILE_ADDRESS.key, currentProfile?.address, addressUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.address != null) {
                    put(UserField.PROFILE_ADDRESS.key, FieldChange(UserField.PROFILE_ADDRESS.key, currentProfile.address, null))
                }
            }
            is FieldUpdate.NoChange -> {}
        }

        when (val pictureUpdate = updates.profilePicture) {
            is FieldUpdate.SetValue -> {
                if (currentProfile?.profilePicture != pictureUpdate.value) {
                    put(UserField.PROFILE_PICTURE.key, FieldChange(UserField.PROFILE_PICTURE.key, currentProfile?.profilePicture, pictureUpdate.value))
                }
            }
            is FieldUpdate.ClearValue -> {
                if (currentProfile?.profilePicture != null) {
                    put(UserField.PROFILE_PICTURE.key, FieldChange(UserField.PROFILE_PICTURE.key, currentProfile.profilePicture, null))
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
                        put(UserField.customAttribute(change.key),
                            FieldChange(UserField.customAttribute(change.key), currentValue, change.value))
                    }
                }
                is AttributeChange.Remove -> {
                    val currentValue = currentAttributes[change.key]
                    if (currentValue != null) {
                        put(UserField.customAttribute(change.key),
                            FieldChange(UserField.customAttribute(change.key), currentValue, null))
                    }
                }
                is AttributeChange.ReplaceAll -> {
                    // For ReplaceAll, we need to track all changes
                    // Keys that exist in current but not in new are removed
                    currentAttributes.forEach { (key, value) ->
                        if (key !in change.attributes) {
                            put(UserField.customAttribute(key),
                                FieldChange(UserField.customAttribute(key), value, null))
                        }
                    }
                    // Keys in new that differ from current
                    change.attributes.forEach { (key, value) ->
                        val currentValue = currentAttributes[key]
                        if (currentValue != value) {
                            put(UserField.customAttribute(key),
                                FieldChange(UserField.customAttribute(key), currentValue, value))
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
