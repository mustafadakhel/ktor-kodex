@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.passwordreset.schema.PasswordResetSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.update.UserField
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.KClass

public class PasswordResetExtension internal constructor(
    private val passwordResetService: PasswordResetService,
    private val tokenCleanupService: TokenCleanupService,
    private val db: KodexDatabase,
    private val schema: PasswordResetSchema,
    private val timeZone: TimeZone
) : EventSubscriberProvider, ServiceProvider {

    override val priority: Int = 100

    override fun getEventSubscribers(): List<EventSubscriber<*>> = listOf(
            object : EventSubscriber<UserEvent.Created> {
                override val eventType = UserEvent.Created::class

                override suspend fun onEvent(event: UserEvent.Created) {
                    val contacts = schema.passwordResetContacts
                    db.transaction {
                        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

                        event.email?.let { email ->
                            insertInto(contacts) {
                                set(contacts.realmId, event.realmId)
                                set(contacts.userId, event.userId)
                                set(contacts.contactType, "EMAIL")
                                set(contacts.contactValue, email)
                                set(contacts.createdAt, now)
                                set(contacts.updatedAt, now)
                            }
                        }

                        event.phone?.let { phone ->
                            insertInto(contacts) {
                                set(contacts.realmId, event.realmId)
                                set(contacts.userId, event.userId)
                                set(contacts.contactType, "PHONE")
                                set(contacts.contactValue, phone)
                                set(contacts.createdAt, now)
                                set(contacts.updatedAt, now)
                            }
                        }
                    }
                }
            },
            object : EventSubscriber<UserEvent.Updated> {
                override val eventType = UserEvent.Updated::class

                override suspend fun onEvent(event: UserEvent.Updated) {
                    val contacts = schema.passwordResetContacts
                    db.transaction {
                        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

                        event.changes.forEach { (fieldName, newValue) ->
                            when (fieldName) {
                                UserField.EMAIL.key -> {
                                    upsert(
                                        contacts,
                                        conflictColumns = listOf(
                                            contacts.realmId,
                                            contacts.userId,
                                            contacts.contactType
                                        )
                                    ) {
                                        set(contacts.realmId, event.realmId)
                                        set(contacts.userId, event.userId)
                                        set(contacts.contactType, "EMAIL")
                                        set(contacts.contactValue, newValue)
                                        set(contacts.updatedAt, now)
                                    }
                                }
                                UserField.PHONE.key -> {
                                    upsert(
                                        contacts,
                                        conflictColumns = listOf(
                                            contacts.realmId,
                                            contacts.userId,
                                            contacts.contactType
                                        )
                                    ) {
                                        set(contacts.realmId, event.realmId)
                                        set(contacts.userId, event.userId)
                                        set(contacts.contactType, "PHONE")
                                        set(contacts.contactValue, newValue)
                                        set(contacts.updatedAt, now)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? = when (type) {
        PasswordResetService::class -> passwordResetService as T
        TokenCleanupService::class -> tokenCleanupService as T
        else -> null
    }
}
