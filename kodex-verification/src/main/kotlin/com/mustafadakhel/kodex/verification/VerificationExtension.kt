package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.verification.database.VerifiableContacts
import com.mustafadakhel.kodex.verification.database.VerificationTokens
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Verification extension that manages contact verification (email, phone, custom attributes).
 *
 * This extension:
 * - Creates verifiable contact records on user registration
 * - Checks if required contacts are verified before allowing login
 * - Automatically sends verification based on configured strategy and policies
 * - Provides a service for managing verification status and tokens
 *
 * Priority: 50 - runs after lockout checks but before normal hooks.
 */
public class VerificationExtension internal constructor(
    internal val verificationService: VerificationService,
    private val config: VerificationConfig,
    private val timeZone: TimeZone
) : UserLifecycleHooks, PersistentExtension, EventSubscriberProvider {

    override val priority: Int = 50

    override fun tables(): List<Table> = listOf(
        VerifiableContacts,
        VerificationTokens
    )

    override suspend fun beforeLogin(identifier: String, metadata: com.mustafadakhel.kodex.extension.LoginMetadata): String {
        // Verification check is performed after user authentication in afterAuthentication()
        return identifier
    }

    override suspend fun beforeUserCreate(
        email: String?,
        phone: String?,
        password: String,
        customAttributes: Map<String, String>?,
        profile: UserProfile?
    ): UserCreateData = UserCreateData(email, phone, customAttributes, profile)

    override suspend fun beforeUserUpdate(
        userId: UUID,
        email: String?,
        phone: String?
    ): UserUpdateData = UserUpdateData(email, phone)

    override suspend fun beforeCustomAttributesUpdate(
        userId: UUID,
        customAttributes: Map<String, String>
    ): Map<String, String> = customAttributes

    override suspend fun afterLoginFailure(identifier: String, metadata: com.mustafadakhel.kodex.extension.LoginMetadata) {
        // No action needed for verification
    }

    override suspend fun afterAuthentication(userId: UUID) {
        // Check if user satisfies verification policy
        if (!verificationService.canLogin(userId)) {
            throw KodexThrowable.Authorization.UnverifiedAccount
        }
    }

    override fun getEventSubscribers(): List<EventSubscriber<out com.mustafadakhel.kodex.event.KodexEvent>> {
        return listOf(
            object : EventSubscriber<UserEvent.Created> {
                override val eventType = UserEvent.Created::class

                override suspend fun onEvent(event: UserEvent.Created) {
                    // Create contact records and auto-send verification based on strategy and policies

                    // 1. Create email contact if provided
                    event.email?.let { emailValue ->
                        val identifier = ContactIdentifier(ContactType.EMAIL)
                        verificationService.setEmail(event.userId, emailValue)

                        // Auto-send if configured
                        if (shouldSendVerification(identifier)) {
                            try {
                                verificationService.sendVerification(event.userId, identifier)
                            } catch (e: Exception) {
                                // Log but don't fail user creation if verification sending fails
                                // TODO: Add proper logging
                            }
                        }
                    }

                    // 2. Create phone contact if provided
                    event.phone?.let { phoneValue ->
                        val identifier = ContactIdentifier(ContactType.PHONE)
                        verificationService.setPhone(event.userId, phoneValue)

                        // Auto-send if configured
                        if (shouldSendVerification(identifier)) {
                            try {
                                verificationService.sendVerification(event.userId, identifier)
                            } catch (e: Exception) {
                                // Log but don't fail user creation if verification sending fails
                                // TODO: Add proper logging
                            }
                        }
                    }
                }

                private fun shouldSendVerification(identifier: ContactIdentifier): Boolean {
                    val policy = config.getPolicy(identifier)

                    // Must have a sender configured
                    if (policy?.sender == null) {
                        return false
                    }

                    // Check strategy
                    return when (config.strategy) {
                        VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED -> {
                            // Send if policy exists and autoSend is true, or if no policy (default to true)
                            policy.autoSend
                        }
                        VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY -> {
                            // Only send if policy exists, is required, and autoSend is true
                            policy.required && policy.autoSend
                        }
                        VerificationConfig.VerificationStrategy.MANUAL -> {
                            // Never auto-send in manual mode
                            false
                        }
                    }
                }
            }
        )
    }
}
