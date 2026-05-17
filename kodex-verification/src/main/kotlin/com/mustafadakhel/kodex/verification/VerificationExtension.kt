package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.model.UserProfile
import java.util.UUID
import kotlin.reflect.KClass

public class VerificationExtension internal constructor(
    private val config: VerificationConfig,
    private val verificationService: VerificationService,
    private val tokenCleanupService: TokenCleanupService
) : UserLifecycleHooks, EventSubscriberProvider, ServiceProvider {

    override val priority: Int = 50

    override suspend fun beforeLogin(identifier: String, metadata: LoginMetadata): String {
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

    override suspend fun afterLoginFailure(
        identifier: String,
        userId: UUID?,
        identifierType: String,
        metadata: LoginMetadata
    ) {
    }

    override suspend fun afterAuthentication(user: AuthenticatedUser, metadata: LoginMetadata) {
        if (!verificationService.canLogin(user.userId)) {
            throw VerificationThrowable.UnverifiedAccount
        }
    }

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> = listOf(
            object : EventSubscriber<UserEvent.Created> {
                override val eventType = UserEvent.Created::class

                override suspend fun onEvent(event: UserEvent.Created) {
                    event.email?.let { emailValue ->
                        verificationService.setEmail(event.userId, emailValue)

                        if (shouldSendVerification(ContactType.Email)) {
                            try {
                                verificationService.sendVerification(event.userId, ContactType.Email)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    event.phone?.let { phoneValue ->
                        verificationService.setPhone(event.userId, phoneValue)

                        if (shouldSendVerification(ContactType.Phone)) {
                            try {
                                verificationService.sendVerification(event.userId, ContactType.Phone)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                private fun shouldSendVerification(contactType: ContactType): Boolean {
                    val policy = config.getPolicy(contactType)

                    if (policy?.sender == null) {
                        return false
                    }

                    if (policy.dependsOn.isNotEmpty()) {
                        return false
                    }

                    return when (config.strategy) {
                        VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED -> {
                            policy.autoSend
                        }
                        VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY -> {
                            policy.required && policy.autoSend
                        }
                        VerificationConfig.VerificationStrategy.MANUAL -> {
                            false
                        }
                    }
                }
            }
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? = when (type) {
        VerificationService::class -> verificationService as T
        TokenCleanupService::class -> tokenCleanupService as T
        else -> null
    }
}
