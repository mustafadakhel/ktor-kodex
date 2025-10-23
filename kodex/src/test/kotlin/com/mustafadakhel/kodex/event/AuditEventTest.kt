package com.mustafadakhel.kodex.event

import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.service.KodexRealmService
import com.mustafadakhel.kodex.service.KodexService
import com.mustafadakhel.kodex.service.argon2HashingService
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.instanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.deleteAll
import kotlin.reflect.KClass

class AuditEventTest : FunSpec({

    lateinit var userRepository: UserRepository
    val realm = Realm("test-realm")

    fun <T : KodexEvent> createServiceWithEventCapture(eventClass: KClass<T>): Pair<KodexService, MutableList<T>> {
        val capturedEvents = mutableListOf<T>()

        val subscriber = object : EventSubscriber<T> {
            override val eventType: KClass<out T> = eventClass
            override suspend fun onEvent(event: T) {
                capturedEvents.add(event)
            }
        }

        val eventSubscriberProvider = object : EventSubscriberProvider {
            override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> = listOf(subscriber)
        }

        val tokenManager = mockk<TokenManager>(relaxed = true)
        coEvery { tokenManager.issueNewTokens(any()) } returns TokenPair(
            access = "mock-access-token",
            refresh = "mock-refresh-token"
        )

        val service = KodexRealmService(
            userRepository = userRepository,
            tokenManager = tokenManager,
            hashingService = argon2HashingService(),
            timeZone = TimeZone.UTC,
            realm = realm,
            extensions = ExtensionRegistry.from(mapOf(com.mustafadakhel.kodex.extension.RealmExtension::class to eventSubscriberProvider))
        )

        return service to capturedEvents
    }

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = false)

        userRepository = databaseUserRepository()

        exposedTransaction {
            userRepository.seedRoles(listOf(
                Role(realm.owner, "Test realm owner"),
                Role("USER", "Standard user"),
                Role("ADMIN", "Administrator")
            ))
        }
    }

    afterEach {
        exposedTransaction {
            Tokens.deleteAll()
            UserRoles.deleteAll()
            UserCustomAttributes.deleteAll()
            UserProfiles.deleteAll()
            Users.deleteAll()
            Roles.deleteAll()
        }
        Db.clearEngine()
    }

    context("User Lifecycle Event Auditing") {
        test("should publish UserEvent.Created on user creation") {
            val (service, capturedEvents) = createServiceWithEventCapture(UserEvent.Created::class)

            val user = service.createUser(
                email = "audit@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                email shouldBe "audit@example.com"
                phone shouldBe null
                realmId shouldBe realm.owner
                eventType shouldBe "USER_CREATED"
                actorType shouldBe "SYSTEM"
            }
        }

        test("should publish UserEvent.RolesUpdated on role change") {
            val (service, capturedEvents) = createServiceWithEventCapture(UserEvent.RolesUpdated::class)

            val user = service.createUser(
                email = "roles@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = listOf("USER"),
                customAttributes = emptyMap(),
                profile = null
            )!!

            runBlocking { delay(100) }
            capturedEvents.clear()

            service.updateUserRoles(user.id, listOf("ADMIN", "USER"))

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                realmId shouldBe realm.owner
                eventType shouldBe "USER_ROLES_UPDATED"
                actorType shouldBe "ADMIN"
            }
        }
    }

    context("Authentication Event Auditing") {
        test("should publish AuthEvent.LoginSuccess on successful authentication") {
            val (service, capturedEvents) = createServiceWithEventCapture(AuthEvent.LoginSuccess::class)

            val user = service.createUser(
                email = "login@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            service.setVerified(user.id, true)

            runBlocking { delay(100) }
            capturedEvents.clear()

            service.tokenByEmail("login@example.com", "SecurePass123")

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                identifier shouldBe "login@example.com"
                method shouldBe "email"
                realmId shouldBe realm.owner
                eventType shouldBe "LOGIN_SUCCESS"
            }
        }

        test("should publish AuthEvent.LoginFailed on authentication failure") {
            val (service, capturedEvents) = createServiceWithEventCapture(AuthEvent.LoginFailed::class)

            val user = service.createUser(
                email = "fail@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            service.setVerified(user.id, true)

            runBlocking { delay(100) }
            capturedEvents.clear()

            runCatching {
                service.tokenByEmail("fail@example.com", "WrongPassword")
            }

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                identifier shouldBe "fail@example.com"
                method shouldBe "email"
                reason shouldBe "Invalid password"
                realmId shouldBe realm.owner
                eventType shouldBe "LOGIN_FAILED"
                actorType shouldBe "USER"
            }
        }

        test("should publish AuthEvent.PasswordChanged on successful password change") {
            val (service, capturedEvents) = createServiceWithEventCapture(AuthEvent.PasswordChanged::class)

            val user = service.createUser(
                email = "change@example.com",
                phone = null,
                password = "OldPassword123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            runBlocking { delay(100) }
            capturedEvents.clear()

            service.changePassword(user.id, "OldPassword123", "NewPassword456")

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                actorId shouldBe user.id
                realmId shouldBe realm.owner
                eventType shouldBe "PASSWORD_CHANGED"
            }
        }

        test("should publish AuthEvent.PasswordChangeFailed on invalid old password") {
            val (service, capturedEvents) = createServiceWithEventCapture(AuthEvent.PasswordChangeFailed::class)

            val user = service.createUser(
                email = "changefail@example.com",
                phone = null,
                password = "OldPassword123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            runBlocking { delay(100) }
            capturedEvents.clear()

            runCatching {
                service.changePassword(user.id, "WrongOldPassword", "NewPassword456")
            }

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                actorId shouldBe user.id
                reason shouldBe "Invalid old password"
                realmId shouldBe realm.owner
                eventType shouldBe "PASSWORD_CHANGE_FAILED"
            }
        }

        test("should publish AuthEvent.PasswordReset on admin password reset") {
            val (service, capturedEvents) = createServiceWithEventCapture(AuthEvent.PasswordReset::class)

            val user = service.createUser(
                email = "reset@example.com",
                phone = null,
                password = "OldPassword123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            runBlocking { delay(100) }
            capturedEvents.clear()

            service.resetPassword(user.id, "NewPassword456")

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].apply {
                userId shouldBe user.id
                realmId shouldBe realm.owner
                eventType shouldBe "PASSWORD_RESET"
                actorType shouldBe "ADMIN"
            }
        }
    }

    context("Audit Trail Aggregation") {
        test("should capture all events with wildcard subscriber") {
            val (service, capturedEvents) = createServiceWithEventCapture(KodexEvent::class)

            val user = service.createUser(
                email = "trail@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            service.setVerified(user.id, true)

            runBlocking { delay(100) }
            capturedEvents.clear()

            service.tokenByEmail("trail@example.com", "SecurePass123")
            service.changePassword(user.id, "SecurePass123", "NewPassword456")

            runBlocking { delay(200) }

            capturedEvents shouldHaveSize 2
            capturedEvents[0] shouldBe instanceOf<AuthEvent.LoginSuccess>()
            capturedEvents[1] shouldBe instanceOf<AuthEvent.PasswordChanged>()
        }
    }

    context("Event Metadata Validation") {
        test("should include proper timestamps in all events") {
            val (service, capturedEvents) = createServiceWithEventCapture(KodexEvent::class)

            val beforeCreation = Clock.System.now()

            val user = service.createUser(
                email = "timestamp@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!

            val afterCreation = Clock.System.now()

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            val event = capturedEvents[0]

            event.timestamp shouldNotBe null
            (event.timestamp >= beforeCreation) shouldBe true
            (event.timestamp <= afterCreation) shouldBe true
        }

        test("should include unique event IDs for multiple events") {
            val (service, capturedEvents) = createServiceWithEventCapture(KodexEvent::class)

            service.createUser(
                email = "unique1@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            service.createUser(
                email = "unique2@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 2
            val eventIds = capturedEvents.map { it.eventId }
            eventIds.distinct() shouldHaveSize 2
        }

        test("should include realm ID in all events") {
            val (service, capturedEvents) = createServiceWithEventCapture(KodexEvent::class)

            service.createUser(
                email = "realm@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            runBlocking { delay(100) }

            capturedEvents shouldHaveSize 1
            capturedEvents[0].realmId shouldBe realm.owner
        }
    }

    context("Subscriber Error Handling") {
        test("should not fail event publishing if audit subscriber throws exception") {
            val successfulEvents = mutableListOf<UserEvent.Created>()

            val failingSubscriber = object : EventSubscriber<UserEvent.Created> {
                override val eventType: KClass<out UserEvent.Created> = UserEvent.Created::class
                override suspend fun onEvent(event: UserEvent.Created) {
                    throw RuntimeException("Audit logging failed")
                }
            }

            val successSubscriber = object : EventSubscriber<UserEvent.Created> {
                override val eventType: KClass<out UserEvent.Created> = UserEvent.Created::class
                override suspend fun onEvent(event: UserEvent.Created) {
                    successfulEvents.add(event)
                }
            }

            val eventSubscriberProvider = object : EventSubscriberProvider {
                override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> =
                    listOf(failingSubscriber, successSubscriber)
            }

            val tokenManager = mockk<TokenManager>(relaxed = true)
            coEvery { tokenManager.issueNewTokens(any()) } returns TokenPair(
                access = "mock-access-token",
                refresh = "mock-refresh-token"
            )

            val service = KodexRealmService(
                userRepository = userRepository,
                tokenManager = tokenManager,
                hashingService = argon2HashingService(),
                timeZone = TimeZone.UTC,
                realm = realm,
                extensions = ExtensionRegistry.from(mapOf(com.mustafadakhel.kodex.extension.RealmExtension::class to eventSubscriberProvider))
            )

            service.createUser(
                email = "error@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            runBlocking { delay(100) }

            successfulEvents shouldHaveSize 1
        }
    }
})
