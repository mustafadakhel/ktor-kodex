package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
import kotlinx.datetime.TimeZone

/**
 * Processes update commands and orchestrates the update workflow.
 *
 * Responsibilities:
 * - Fetch current user state
 * - Detect changes using ChangeTracker
 * - Execute lifecycle hooks
 * - Apply updates through repository
 * - Handle version conflicts (optimistic locking)
 * - Return detailed results with change tracking
 */
internal class UpdateCommandProcessor(
    private val userRepository: UserRepository,
    private val hookExecutor: HookExecutor,
    private val changeTracker: ChangeTracker,
    private val timeZone: TimeZone
) {

    /**
     * Executes an update command and returns the result.
     */
    suspend fun execute(command: UpdateCommand): UpdateResult {
        // 1. Fetch current user state
        val currentUserEntity = userRepository.findFullById(command.userId)
            ?: return UpdateResult.Failure.NotFound(command.userId)

        // 2. Execute beforeUpdate hooks to validate and transform values
        // Catch validation exceptions and convert to ValidationFailed results
        val transformedCommand = try {
            when (command) {
                is UpdateUserFields -> executeUserFieldHooks(command)
                is UpdateProfileFields -> executeProfileFieldHooks(command)
                is UpdateAttributes -> executeAttributeHooks(command)
                is UpdateUserBatch -> executeBatchHooks(command)
            }
        } catch (e: KodexThrowable.Validation) {
            return convertValidationExceptionToResult(e)
        }

        // 3. Detect what will actually change after transformation
        val changeSet = changeTracker.detectChanges(currentUserEntity, transformedCommand)

        // 4. If no change after hooks, return early
        if (changeSet.changedFields.isEmpty()) {
            return UpdateResult.Success(
                user = currentUserEntity.toFullUser(),
                changes = changeSet
            )
        }

        // 5. Apply the update through repository
        val result = when (transformedCommand) {
            is UpdateUserFields -> applyUserFieldUpdates(currentUserEntity, transformedCommand.fields)
            is UpdateProfileFields -> applyProfileFieldUpdates(currentUserEntity, transformedCommand.fields)
            is UpdateAttributes -> applyAttributeUpdates(currentUserEntity, transformedCommand.changes)
            is UpdateUserBatch -> applyBatchUpdate(currentUserEntity, transformedCommand)
        }

        return result
    }

    /**
     * Converts validation exceptions to ValidationFailed results.
     */
    private fun convertValidationExceptionToResult(e: KodexThrowable.Validation): UpdateResult.Failure.ValidationFailed {
        val errors = when (e) {
            is KodexThrowable.Validation.ValidationFailed -> listOf(
                ValidationError(field = "unknown", message = e.message ?: "Validation failed", code = "VALIDATION_FAILED")
            )
            is KodexThrowable.Validation.InvalidEmail -> listOf(
                ValidationError(field = "email", message = e.errors.joinToString(", "), code = "INVALID_EMAIL")
            )
            is KodexThrowable.Validation.InvalidPhone -> listOf(
                ValidationError(field = "phone", message = e.errors.joinToString(", "), code = "INVALID_PHONE")
            )
            is KodexThrowable.Validation.WeakPassword -> listOf(
                ValidationError(field = "password", message = e.feedback.joinToString(". "), code = "WEAK_PASSWORD")
            )
            is KodexThrowable.Validation.InvalidCustomAttribute -> listOf(
                ValidationError(field = "customAttributes.${e.key}", message = e.errors.joinToString(", "), code = "INVALID_ATTRIBUTE")
            )
            is KodexThrowable.Validation.InvalidInput -> listOf(
                ValidationError(field = e.field, message = e.errors.joinToString(", "), code = "INVALID_INPUT")
            )
        }
        return UpdateResult.Failure.ValidationFailed(errors)
    }

    /**
     * Executes hooks for user field updates.
     * Converts FieldUpdate to nullable values, applies hooks, converts back.
     */
    private suspend fun executeUserFieldHooks(command: UpdateUserFields): UpdateUserFields {
        // Extract actual values from FieldUpdate (NoChange -> null for hooks)
        val emailValue = when (val update = command.fields.email) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> null
        }

        val phoneValue = when (val update = command.fields.phone) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> null
        }

        // Only call hooks if there are actual changes
        if (emailValue == null && phoneValue == null) {
            return command
        }

        // Execute hooks
        val transformed = hookExecutor.executeBeforeUserUpdate(
            userId = command.userId,
            email = emailValue,
            phone = phoneValue
        )

        // Apply transformations back to FieldUpdate
        val newFields = command.fields.copy(
            email = if (emailValue != null) FieldUpdate.SetValue(transformed.email ?: emailValue) else command.fields.email,
            phone = if (phoneValue != null) FieldUpdate.SetValue(transformed.phone ?: phoneValue) else command.fields.phone
        )

        return command.copy(fields = newFields)
    }

    /**
     * Executes hooks for profile field updates.
     */
    private suspend fun executeProfileFieldHooks(command: UpdateProfileFields): UpdateProfileFields {
        // Extract values
        val firstName = when (val update = command.fields.firstName) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> null
        }

        val lastName = when (val update = command.fields.lastName) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> null
        }

        val address = when (val update = command.fields.address) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> null
        }

        val profilePicture = when (val update = command.fields.profilePicture) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> null
        }

        // Only call hooks if there are changes
        if (firstName == null && lastName == null && address == null && profilePicture == null) {
            return command
        }

        // Execute hooks
        val transformed = hookExecutor.executeBeforeProfileUpdate(
            userId = command.userId,
            firstName = firstName,
            lastName = lastName,
            address = address,
            profilePicture = profilePicture
        )

        // Apply transformations
        val newFields = command.fields.copy(
            firstName = if (firstName != null) FieldUpdate.SetValue(transformed.firstName ?: firstName) else command.fields.firstName,
            lastName = if (lastName != null) FieldUpdate.SetValue(transformed.lastName ?: lastName) else command.fields.lastName,
            address = if (address != null) FieldUpdate.SetValue(transformed.address ?: address) else command.fields.address,
            profilePicture = if (profilePicture != null) FieldUpdate.SetValue(transformed.profilePicture ?: profilePicture) else command.fields.profilePicture
        )

        return command.copy(fields = newFields)
    }

    /**
     * Executes hooks for custom attribute updates.
     */
    private suspend fun executeAttributeHooks(command: UpdateAttributes): UpdateAttributes {
        // Build the attribute map from changes
        val attributesToValidate = buildMap {
            command.changes.changes.forEach { change ->
                when (change) {
                    is AttributeChange.Set -> put(change.key, change.value)
                    is AttributeChange.Remove -> {} // Don't validate removed attributes
                    is AttributeChange.ReplaceAll -> putAll(change.attributes)
                }
            }
        }

        if (attributesToValidate.isEmpty()) {
            return command
        }

        // Execute hooks
        val transformed = hookExecutor.executeBeforeCustomAttributesUpdate(
            userId = command.userId,
            customAttributes = attributesToValidate
        )

        // Apply transformations
        val newChanges = command.changes.changes.map { change ->
            when (change) {
                is AttributeChange.Set -> {
                    val newValue = transformed[change.key] ?: change.value
                    AttributeChange.Set(change.key, newValue)
                }
                is AttributeChange.ReplaceAll -> AttributeChange.ReplaceAll(transformed)
                is AttributeChange.Remove -> change
            }
        }

        return command.copy(changes = AttributeChanges(newChanges))
    }

    /**
     * Executes hooks for batch updates.
     */
    private suspend fun executeBatchHooks(command: UpdateUserBatch): UpdateUserBatch {
        val transformedUserFields = command.userFields?.let {
            executeUserFieldHooks(UpdateUserFields(command.userId, it)).fields
        }

        val transformedProfileFields = command.profileFields?.let {
            executeProfileFieldHooks(UpdateProfileFields(command.userId, it)).fields
        }

        val transformedAttributeChanges = command.attributeChanges?.let {
            executeAttributeHooks(UpdateAttributes(command.userId, it)).changes
        }

        return command.copy(
            userFields = transformedUserFields,
            profileFields = transformedProfileFields,
            attributeChanges = transformedAttributeChanges
        )
    }

    /**
     * Applies user field updates.
     */
    private fun applyUserFieldUpdates(
        current: FullUserEntity,
        updates: UserFieldUpdates
    ): UpdateResult {
        // Determine new values
        // Apply update through repository
        val repositoryResult = userRepository.updateById(
            userId = current.id,
            email = updates.email,
            phone = updates.phone,
            status = updates.status,
            currentTime = now(timeZone)
        )

        return when (repositoryResult) {
            is UserRepository.UpdateUserResult.Success -> {
                // Fetch updated user
                val updatedUserEntity = userRepository.findFullById(current.id)
                    ?: return UpdateResult.Failure.Unknown("User disappeared after update")

                // Recalculate actual changes (in case repository modified something)
                val actualChanges = changeTracker.detectChanges(current, UpdateUserFields(current.id, updates))

                UpdateResult.Success(
                    user = updatedUserEntity.toFullUser(),
                    changes = actualChanges
                )
            }
            is UserRepository.UpdateUserResult.EmailAlreadyExists ->
                UpdateResult.Failure.ConstraintViolation("email", "Email already exists")
            is UserRepository.UpdateUserResult.PhoneAlreadyExists ->
                UpdateResult.Failure.ConstraintViolation("phone", "Phone already exists")
            is UserRepository.UpdateUserResult.NotFound ->
                UpdateResult.Failure.NotFound(current.id)
            is UserRepository.UpdateUserResult.InvalidRole ->
                UpdateResult.Failure.Unknown("Invalid role: ${repositoryResult.roleName}")
        }
    }

    /**
     * Applies profile field updates.
     */
    private fun applyProfileFieldUpdates(
        current: FullUserEntity,
        updates: ProfileFieldUpdates
    ): UpdateResult {
        // Build new profile by applying updates to current profile
        val currentProfile = current.profile

        val newFirstName = when (val update = updates.firstName) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> currentProfile?.firstName
        }

        val newLastName = when (val update = updates.lastName) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> currentProfile?.lastName
        }

        val newAddress = when (val update = updates.address) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> currentProfile?.address
        }

        val newProfilePicture = when (val update = updates.profilePicture) {
            is FieldUpdate.SetValue -> update.value
            is FieldUpdate.ClearValue -> null
            is FieldUpdate.NoChange -> currentProfile?.profilePicture
        }

        val newProfile = UserProfile(
            firstName = newFirstName,
            lastName = newLastName,
            address = newAddress,
            profilePicture = newProfilePicture
        )

        val repositoryResult = userRepository.updateProfileByUserId(current.id, newProfile)

        return when (repositoryResult) {
            is UserRepository.UpdateProfileResult.Success -> {
                val updatedUserEntity = userRepository.findFullById(current.id)
                    ?: return UpdateResult.Failure.Unknown("User disappeared after update")

                val actualChanges = changeTracker.detectChanges(current, UpdateProfileFields(current.id, updates))

                UpdateResult.Success(
                    user = updatedUserEntity.toFullUser(),
                    changes = actualChanges
                )
            }
            is UserRepository.UpdateProfileResult.NotFound ->
                UpdateResult.Failure.NotFound(current.id)
        }
    }

    /**
     * Applies custom attribute updates.
     */
    private fun applyAttributeUpdates(
        current: FullUserEntity,
        changes: AttributeChanges
    ): UpdateResult {
        val currentAttrs = current.customAttributes ?: emptyMap()

        // Determine the final attribute state based on changes
        val hasReplaceAll = changes.changes.any { it is AttributeChange.ReplaceAll }

        val repositoryResult = if (hasReplaceAll) {
            // Use replaceAll for ReplaceAll operations
            val replaceChange = changes.changes.first { it is AttributeChange.ReplaceAll } as AttributeChange.ReplaceAll
            userRepository.replaceAllCustomAttributesByUserId(current.id, replaceChange.attributes)
        } else {
            // Apply individual changes
            val newAttrs = currentAttrs.toMutableMap()
            changes.changes.forEach { change ->
                when (change) {
                    is AttributeChange.Set -> newAttrs[change.key] = change.value
                    is AttributeChange.Remove -> newAttrs.remove(change.key)
                    is AttributeChange.ReplaceAll -> {} // Already handled
                }
            }
            userRepository.updateCustomAttributesByUserId(current.id, newAttrs)
        }

        return when (repositoryResult) {
            is UserRepository.UpdateUserResult.Success -> {
                val updatedUserEntity = userRepository.findFullById(current.id)
                    ?: return UpdateResult.Failure.Unknown("User disappeared after update")

                val actualChanges = changeTracker.detectChanges(current, UpdateAttributes(current.id, changes))

                UpdateResult.Success(
                    user = updatedUserEntity.toFullUser(),
                    changes = actualChanges
                )
            }
            is UserRepository.UpdateUserResult.NotFound ->
                UpdateResult.Failure.NotFound(current.id)
            else -> UpdateResult.Failure.Unknown("Unexpected repository result")
        }
    }

    /**
     * Applies a batch update atomically in a single transaction.
     * All updates succeed or all fail together - no partial updates.
     */
    private fun applyBatchUpdate(
        current: FullUserEntity,
        batch: UpdateUserBatch
    ): UpdateResult {
        // Extract values from batch fields
        // Execute batch update atomically in single transaction
        val repositoryResult = userRepository.updateBatch(
            userId = current.id,
            email = batch.userFields?.email ?: FieldUpdate.NoChange(),
            phone = batch.userFields?.phone ?: FieldUpdate.NoChange(),
            status = batch.userFields?.status ?: FieldUpdate.NoChange(),
            profile = if (batch.profileFields?.hasChanges() == true) {
                val currentProfile = current.profile
                FieldUpdate.SetValue(
                    UserProfile(
                        firstName = when (val update = batch.profileFields.firstName) {
                            is FieldUpdate.SetValue -> update.value
                            is FieldUpdate.ClearValue -> null
                            is FieldUpdate.NoChange -> currentProfile?.firstName
                        },
                        lastName = when (val update = batch.profileFields.lastName) {
                            is FieldUpdate.SetValue -> update.value
                            is FieldUpdate.ClearValue -> null
                            is FieldUpdate.NoChange -> currentProfile?.lastName
                        },
                        address = when (val update = batch.profileFields.address) {
                            is FieldUpdate.SetValue -> update.value
                            is FieldUpdate.ClearValue -> null
                            is FieldUpdate.NoChange -> currentProfile?.address
                        },
                        profilePicture = when (val update = batch.profileFields.profilePicture) {
                            is FieldUpdate.SetValue -> update.value
                            is FieldUpdate.ClearValue -> null
                            is FieldUpdate.NoChange -> currentProfile?.profilePicture
                        }
                    )
                )
            } else {
                FieldUpdate.NoChange()
            },
            customAttributes = if (batch.attributeChanges?.hasChanges() == true) {
                val currentAttrs = current.customAttributes ?: emptyMap()
                val newAttrs = currentAttrs.toMutableMap()

                batch.attributeChanges.changes.forEach { change ->
                    when (change) {
                        is AttributeChange.Set -> newAttrs[change.key] = change.value
                        is AttributeChange.Remove -> newAttrs.remove(change.key)
                        is AttributeChange.ReplaceAll -> {
                            newAttrs.clear()
                            newAttrs.putAll(change.attributes)
                        }
                    }
                }
                FieldUpdate.SetValue(newAttrs)
            } else {
                FieldUpdate.NoChange()
            },
            currentTime = now(timeZone)
        )

        return when (repositoryResult) {
            is UserRepository.UpdateUserResult.Success -> {
                val updatedUserEntity = userRepository.findFullById(current.id)
                    ?: return UpdateResult.Failure.Unknown("User disappeared after update")

                val actualChanges = changeTracker.detectBatchChanges(current.toFullUser(), batch)

                UpdateResult.Success(
                    user = updatedUserEntity.toFullUser(),
                    changes = actualChanges
                )
            }
            is UserRepository.UpdateUserResult.EmailAlreadyExists ->
                UpdateResult.Failure.ConstraintViolation("email", "Email already exists")
            is UserRepository.UpdateUserResult.PhoneAlreadyExists ->
                UpdateResult.Failure.ConstraintViolation("phone", "Phone already exists")
            is UserRepository.UpdateUserResult.NotFound ->
                UpdateResult.Failure.NotFound(current.id)
            is UserRepository.UpdateUserResult.InvalidRole ->
                UpdateResult.Failure.Unknown("Invalid role: ${repositoryResult.roleName}")
        }
    }
}
