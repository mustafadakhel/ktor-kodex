package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.passwordreset.schema.PasswordResetSchema
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.DatabaseAwareExtension
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.upsert
import kotlin.reflect.KClass

public class PasswordResetExtension internal constructor(
    private val config: PasswordResetConfigData,
    private val passwordResetSender: PasswordResetSender,
    private val timeZone: TimeZone,
    private val eventBus: EventBus?,
    private val realm: String,
    private val rateLimiter: RateLimiter
) : PersistentExtension, EventSubscriberProvider, ServiceProvider, DatabaseAwareExtension {

    override val priority: Int = 100

    public lateinit var passwordResetService: PasswordResetService
        private set
    public lateinit var tokenCleanupService: TokenCleanupService
        private set

    private lateinit var db: KodexDatabase
    private lateinit var schema: PasswordResetSchema

    override fun createSchema(core: CoreSchema): ExtensionSchema = PasswordResetSchema(core)

    override fun initialize(db: KodexDatabase) {
        this.db = db
        this.schema = db.schema<PasswordResetSchema>()

        passwordResetService = DefaultPasswordResetService(
            db = db,
            schema = schema,
            config = config,
            passwordResetSender = passwordResetSender,
            timeZone = timeZone,
            eventBus = eventBus,
            realm = realm,
            rateLimiter = rateLimiter
        )

        tokenCleanupService = DefaultTokenCleanupService(
            db = db,
            schema = schema,
            timeZone = timeZone,
            eventBus = eventBus,
            realm = realm
        )
    }

    override fun getEventSubscribers(): List<EventSubscriber<*>> {
        return listOf(
            object : EventSubscriber<UserEvent.Created> {
                override val eventType = UserEvent.Created::class

                override suspend fun onEvent(event: UserEvent.Created) {
                    val contacts = schema.passwordResetContacts
                    db.transaction {
                        val now = CurrentKotlinInstant.toLocalDateTime(timeZone)

                        event.email?.let { email ->
                            contacts.insert {
                                it[contacts.realmId] = event.realmId
                                it[contacts.userId] = event.userId
                                it[contacts.contactType] = "EMAIL"
                                it[contacts.contactValue] = email
                                it[contacts.createdAt] = now
                                it[contacts.updatedAt] = now
                            }
                        }

                        event.phone?.let { phone ->
                            contacts.insert {
                                it[contacts.realmId] = event.realmId
                                it[contacts.userId] = event.userId
                                it[contacts.contactType] = "PHONE"
                                it[contacts.contactValue] = phone
                                it[contacts.createdAt] = now
                                it[contacts.updatedAt] = now
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

                        event.fieldChanges.forEach { change ->
                            when (change.fieldName) {
                                "email" -> {
                                    val newEmail = change.newValue as? String
                                    if (newEmail != null) {
                                        contacts.upsert(
                                            keys = arrayOf(
                                                contacts.realmId,
                                                contacts.userId,
                                                contacts.contactType
                                            )
                                        ) {
                                            it[contacts.realmId] = event.realmId
                                            it[contacts.userId] = event.userId
                                            it[contacts.contactType] = "EMAIL"
                                            it[contacts.contactValue] = newEmail
                                            it[contacts.updatedAt] = now
                                        }
                                    }
                                }
                                "phone" -> {
                                    val newPhone = change.newValue as? String
                                    if (newPhone != null) {
                                        contacts.upsert(
                                            keys = arrayOf(
                                                contacts.realmId,
                                                contacts.userId,
                                                contacts.contactType
                                            )
                                        ) {
                                            it[contacts.realmId] = event.realmId
                                            it[contacts.userId] = event.userId
                                            it[contacts.contactType] = "PHONE"
                                            it[contacts.contactValue] = newPhone
                                            it[contacts.updatedAt] = now
                                        }
                                    }
                                }
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
