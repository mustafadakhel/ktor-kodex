package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetContacts
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetTokens
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import kotlin.reflect.KClass

/**
 * Password reset extension that manages password reset requests via email/phone.
 *
 * This extension:
 * - Creates password reset contact records on user registration
 * - Provides a service for managing password reset tokens
 * - Handles rate limiting for reset attempts
 *
 * Priority: 100 - runs independently, no conflicts with other extensions.
 */
public class PasswordResetExtension internal constructor(
    public val passwordResetService: PasswordResetService,
    public val tokenCleanupService: TokenCleanupService,
    private val timeZone: TimeZone
) : PersistentExtension, EventSubscriberProvider, ServiceProvider {

    override val priority: Int = 100

    override fun tables(): List<Table> = listOf(
        PasswordResetContacts,
        PasswordResetTokens
    )

    override fun getEventSubscribers(): List<EventSubscriber<*>> {
        return listOf(
            object : EventSubscriber<UserEvent.Created> {
                override val eventType = UserEvent.Created::class

                override suspend fun onEvent(event: UserEvent.Created) {
                    // Store contacts for password reset
                    // Note: Only handles UserEvent.Created. Contact updates via UserEvent.Updated
                    // are not yet implemented - users must reset password using their original contact.
                    kodexTransaction {
                        val now = Clock.System.now().toLocalDateTime(timeZone)

                        event.email?.let { email ->
                            PasswordResetContacts.insert {
                                it[PasswordResetContacts.userId] = event.userId
                                it[PasswordResetContacts.contactType] = "EMAIL"
                                it[PasswordResetContacts.contactValue] = email
                                it[PasswordResetContacts.createdAt] = now
                                it[PasswordResetContacts.updatedAt] = now
                            }
                        }

                        event.phone?.let { phone ->
                            PasswordResetContacts.insert {
                                it[PasswordResetContacts.userId] = event.userId
                                it[PasswordResetContacts.contactType] = "PHONE"
                                it[PasswordResetContacts.contactValue] = phone
                                it[PasswordResetContacts.createdAt] = now
                                it[PasswordResetContacts.updatedAt] = now
                            }
                        }
                    }
                }
            }
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? {
        return when (type) {
            PasswordResetService::class -> passwordResetService as T
            TokenCleanupService::class -> tokenCleanupService as T
            else -> null
        }
    }
}
