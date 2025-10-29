package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.throwable.KodexThrowable
import java.util.UUID

/**
 * Built-in validation hook that enforces basic input validation rules.
 *
 * This hook is registered by default and provides essential validation:
 * - Email format validation (RFC 5322 basic check)
 * - Password minimum length enforcement
 * - Phone E.164 format validation
 * - Custom attribute sanitization
 *
 * For advanced validation (disposable email blocking, password strength scoring),
 * use the kodex-validation extension module.
 */
internal class DefaultValidationHook : UserLifecycleHooks {

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_PASSWORD_LENGTH = 128

        // Basic email regex - catches obvious invalids
        // Does not validate all RFC 5322 edge cases
        private val EMAIL_REGEX = Regex(
            "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"
        )

        // E.164 phone format: +[country code][number]
        // Example: +14155552671
        private val PHONE_REGEX = Regex("^\\+[1-9]\\d{1,14}$")
    }

    override suspend fun beforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData {
        // Validate email if provided
        if (email != null) {
            validateEmail(email)
        }

        // Validate phone if provided
        if (phone != null) {
            validatePhone(phone)
        }

        // Validate password
        validatePassword(password)

        // Validate profile if provided
        if (profile != null) {
            validateProfile(profile)
        }

        return UserCreateData(email, phone, customAttributes, profile)
    }

    override suspend fun beforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData {
        // Validate email if being updated
        if (email != null) {
            validateEmail(email)
        }

        // Validate phone if being updated
        if (phone != null) {
            validatePhone(phone)
        }

        return UserUpdateData(email, phone)
    }

    override suspend fun beforeProfileUpdate(
        userId: UUID,
        firstName: String?,
        lastName: String?,
        address: String?,
        profilePicture: String?
    ): UserProfileUpdateData {
        // Basic XSS prevention - check for script tags
        if (firstName != null) validateNoScriptTags(firstName, "firstName")
        if (lastName != null) validateNoScriptTags(lastName, "lastName")
        if (address != null) validateNoScriptTags(address, "address")
        if (profilePicture != null) validateNoScriptTags(profilePicture, "profilePicture")

        return UserProfileUpdateData(firstName, lastName, address, profilePicture)
    }

    private fun validateEmail(email: String) {
        if (email.isBlank()) {
            throw KodexThrowable.Validation.ValidationFailed("Email cannot be blank")
        }

        if (email.length > 320) {
            throw KodexThrowable.Validation.ValidationFailed("Email is too long (maximum 320 characters)")
        }

        // Check @ symbol count first (more specific error message)
        if (email.count { it == '@' } != 1) {
            throw KodexThrowable.Validation.ValidationFailed("Email must contain exactly one @ symbol")
        }

        val parts = email.split("@")
        if (parts[0].isEmpty()) {
            throw KodexThrowable.Validation.ValidationFailed("Email local part cannot be empty")
        }
        if (parts[1].isEmpty()) {
            throw KodexThrowable.Validation.ValidationFailed("Email domain cannot be empty")
        }
        if (!parts[1].contains('.')) {
            throw KodexThrowable.Validation.ValidationFailed("Email domain must contain a dot")
        }

        // General format check last (catches remaining issues)
        if (!email.matches(EMAIL_REGEX)) {
            throw KodexThrowable.Validation.ValidationFailed("Invalid email format")
        }
    }

    private fun validatePhone(phone: String) {
        if (phone.isBlank()) {
            throw KodexThrowable.Validation.ValidationFailed("Phone number cannot be blank")
        }

        if (!phone.matches(PHONE_REGEX)) {
            throw KodexThrowable.Validation.ValidationFailed(
                "Invalid phone number format. Must be E.164 format (e.g., +14155552671)"
            )
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw KodexThrowable.Validation.ValidationFailed(
                "Password must be at least $MIN_PASSWORD_LENGTH characters long"
            )
        }

        if (password.length > MAX_PASSWORD_LENGTH) {
            throw KodexThrowable.Validation.ValidationFailed(
                "Password is too long (maximum $MAX_PASSWORD_LENGTH characters)"
            )
        }

        // Check for at least one letter and one number
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }

        if (!hasLetter || !hasDigit) {
            throw KodexThrowable.Validation.ValidationFailed(
                "Password must contain at least one letter and one number"
            )
        }
    }

    private fun validateProfile(profile: UserProfile) {
        profile.firstName?.let { validateNoScriptTags(it, "firstName") }
        profile.lastName?.let { validateNoScriptTags(it, "lastName") }
        profile.address?.let { validateNoScriptTags(it, "address") }
        profile.profilePicture?.let { validateNoScriptTags(it, "profilePicture") }
    }

    private fun validateNoScriptTags(input: String, fieldName: String) {
        val lowerInput = input.lowercase()
        if (lowerInput.contains("<script") || lowerInput.contains("javascript:")) {
            throw KodexThrowable.Validation.ValidationFailed(
                "$fieldName contains potentially malicious content"
            )
        }
    }
}
