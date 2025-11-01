package com.mustafadakhel.kodex.validation

import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.throwable.KodexThrowable
import java.util.*

/**
 * Validation extension that hooks into user lifecycle events.
 * Validates and sanitizes user input before persistence.
 */
public class ValidationExtension internal constructor(
    private val service: ValidationService
) : UserLifecycleHooks {

    override suspend fun beforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData {
        // Validate email if provided
        email?.let {
            val emailResult = service.validateEmail(it)
            if (!emailResult.isValid) {
                throw KodexThrowable.Validation.InvalidEmail(
                    email = it,
                    errors = emailResult.errors.map { error -> error.message }
                )
            }
        }

        // Validate phone if provided
        phone?.let {
            val phoneResult = service.validatePhone(it)
            if (!phoneResult.isValid) {
                throw KodexThrowable.Validation.InvalidPhone(
                    phone = it,
                    errors = phoneResult.errors.map { error -> error.message }
                )
            }
        }

        // Validate password
        val passwordResult = service.validatePassword(password)
        if (!passwordResult.isValid) {
            val strength = service.analyzePasswordStrength(password)
            throw KodexThrowable.Validation.WeakPassword(
                score = strength.score,
                feedback = strength.feedback
            )
        }

        // Validate and sanitize custom attributes if provided
        val sanitizedAttributes = customAttributes?.let { attrs ->
            val attrsResult = service.validateCustomAttributes(attrs)
            if (!attrsResult.isValid) {
                throw KodexThrowable.Validation.InvalidInput(
                    field = "customAttributes",
                    errors = attrsResult.errors.map { error -> error.message }
                )
            }

            // Sanitize values to prevent XSS
            attrs.mapValues { (_, value) ->
                service.sanitizeHtml(value, InputContext.PLAIN_TEXT)
            }
        }

        return UserCreateData(
            email = email,
            phone = phone,
            customAttributes = sanitizedAttributes,
            profile = profile
        )
    }

    override suspend fun beforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData {
        // Validate email if provided
        email?.let {
            val emailResult = service.validateEmail(it)
            if (!emailResult.isValid) {
                throw KodexThrowable.Validation.InvalidEmail(
                    email = it,
                    errors = emailResult.errors.map { error -> error.message }
                )
            }
        }

        // Validate phone if provided
        phone?.let {
            val phoneResult = service.validatePhone(it)
            if (!phoneResult.isValid) {
                throw KodexThrowable.Validation.InvalidPhone(
                    phone = it,
                    errors = phoneResult.errors.map { error -> error.message }
                )
            }
        }

        return UserUpdateData(email = email, phone = phone)
    }

    override suspend fun beforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> {
        // Validate custom attributes
        val attrsResult = service.validateCustomAttributes(customAttributes)
        if (!attrsResult.isValid) {
            throw KodexThrowable.Validation.InvalidInput(
                field = "customAttributes",
                errors = attrsResult.errors.map { error -> error.message }
            )
        }

        // Sanitize values to prevent XSS
        return customAttributes.mapValues { (_, value) ->
            service.sanitizeHtml(value, InputContext.PLAIN_TEXT)
        }
    }

    override suspend fun beforeLogin(identifier: String, metadata: com.mustafadakhel.kodex.extension.LoginMetadata): String = identifier

    override suspend fun afterLoginFailure(
        identifier: String,
        userId: java.util.UUID?,
        identifierType: String,
        metadata: com.mustafadakhel.kodex.extension.LoginMetadata
    ) {
        // Extension point for future validation tracking
    }
}
