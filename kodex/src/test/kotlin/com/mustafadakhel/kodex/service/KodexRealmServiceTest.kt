package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable.*
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.update.*
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.instanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.deleteAll
import java.util.*

class KodexRealmServiceTest : FunSpec({

    lateinit var service: KodexService
    lateinit var userRepository: UserRepository
    lateinit var hashingService: HashingService
    lateinit var tokenManager: TokenManager
    val now = LocalDateTime(2024, 1, 15, 10, 30)
    val realm = Realm("test-realm")

    beforeEach {
        // H2 + Exposed setup
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = false)

        // Create service with dependencies
        userRepository = databaseUserRepository()
        hashingService = argon2HashingService()

        // Seed default roles to avoid RoleNotFound errors
        // Note: createUser automatically adds realm.owner as a role
        exposedTransaction {
            userRepository.seedRoles(listOf(
                Role(realm.owner, "Realm owner role"),
                Role("USER", "Standard user"),
                Role("ADMIN", "Administrator"),
                Role("MODERATOR", "Moderator")
            ))
        }

        // Mock token manager for now (will be tested separately in DefaultTokenManagerTest)
        tokenManager = mockk<TokenManager>(relaxed = true)
        coEvery { tokenManager.issueNewTokens(any()) } returns TokenPair(
            access = "mock-access-token",
            refresh = "mock-refresh-token"
        )

        service = KodexRealmService(
            userRepository = userRepository,
            tokenManager = tokenManager,
            hashingService = hashingService,
            timeZone = TimeZone.UTC,
            realm = realm,
            extensions = ExtensionRegistry.empty()
        )
    }

    afterEach {
        exposedTransaction {
            UserRoles.deleteAll()
            UserCustomAttributes.deleteAll()
            UserProfiles.deleteAll()
            Tokens.deleteAll()
            Users.deleteAll()
            Roles.deleteAll()
        }
        Db.clearEngine()
    }

    context("User Creation") {
        test("should create user with email successfully") {
            val user = service.createUser(
                email = "john@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            user.shouldNotBeNull()
            user.email shouldBe "john@example.com"
            user.phoneNumber.shouldBeNull()
            user.isVerified shouldBe false
        }

        test("should create user with phone successfully") {
            val user = service.createUser(
                email = null,
                phone = "+1234567890",
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            user.shouldNotBeNull()
            user.phoneNumber shouldBe "+1234567890"
            user.email.shouldBeNull()
        }

        test("should create user with profile and attributes") {
            val profile = UserProfile("John", "Doe", "123 Main St", "pic.jpg")
            val attrs = mapOf("dept" to "Engineering", "level" to "Senior")

            val user = service.createUser(
                email = "john@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = attrs,
                profile = profile
            )

            user.shouldNotBeNull()
            val fullUser = service.getFullUser(user.id)
            fullUser.profile shouldBe profile
            fullUser.customAttributes.shouldNotBeNull()
            fullUser.customAttributes!! shouldContainExactly attrs
        }

        test("should create user with roles") {
            // Roles already seeded in beforeEach
            // Note: realm.owner role is automatically added to all users
            val user = service.createUser(
                email = "admin@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = listOf("USER", "ADMIN"),
                customAttributes = emptyMap(),
                profile = null
            )

            user.shouldNotBeNull()
            val fullUser = service.getFullUser(user.id)
            fullUser.roles.map { it.name } shouldContainExactlyInAnyOrder listOf(realm.owner, "USER", "ADMIN")
        }

        test("should throw when creating user with duplicate email") {
            service.createUser(
                email = "john@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )

            shouldThrow<EmailAlreadyExists> {
                service.createUser(
                    email = "john@example.com",
                    phone = null,
                    password = "DifferentPass123",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
            }
        }

        // Note: System currently allows creating users with neither email nor phone (both nullable)
        // This might be a design decision or a validation gap to address in the future
    }

    context("User Retrieval") {
        test("should get user by ID") {
            val createdUser = service.createUser(
                email = "john@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )
            createdUser.shouldNotBeNull()

            val user = service.getUser(createdUser.id)
            user.id shouldBe createdUser.id
            user.email shouldBe "john@example.com"
        }

        test("should return null when user not found with getUserOrNull") {
            val nonExistentId = UUID.randomUUID()
            val user = service.getUserOrNull(nonExistentId)
            user.shouldBeNull()
        }

        test("should throw UserNotFound when user doesn't exist") {
            val nonExistentId = UUID.randomUUID()
            shouldThrow<UserNotFound> {
                service.getUser(nonExistentId)
            }
        }

        test("should get all users") {
            service.createUser("user1@example.com", null, "pass123", emptyList(), emptyMap(), null)
            service.createUser("user2@example.com", null, "pass456", emptyList(), emptyMap(), null)
            service.createUser("user3@example.com", null, "pass789", emptyList(), emptyMap(), null)

            val users = service.getAllUsers()
            users.size shouldBe 3
            users.map { it.email } shouldContainExactlyInAnyOrder listOf(
                "user1@example.com",
                "user2@example.com",
                "user3@example.com"
            )
        }

        test("should get user by email") {
            service.createUser("john@example.com", null, "pass123", emptyList(), emptyMap(), null)

            val user = service.getUserByEmail("john@example.com")
            user.email shouldBe "john@example.com"
        }

        test("should get user by phone") {
            service.createUser(null, "+1234567890", "pass123", emptyList(), emptyMap(), null)

            val user = service.getUserByPhone("+1234567890")
            user.phoneNumber shouldBe "+1234567890"
        }
    }

    context("Authentication") {
        test("should authenticate user with valid email and password") {
            val user = service.createUser("john@example.com", null, "SecurePass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()
            service.setVerified(user.id, true)  // Verify user before authentication

            val tokenPair = service.tokenByEmail("john@example.com", "SecurePass123")
            tokenPair.access shouldBe "mock-access-token"
            tokenPair.refresh shouldBe "mock-refresh-token"
        }

        test("should authenticate user with valid phone and password") {
            val user = service.createUser(null, "+1234567890", "SecurePass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()
            service.setVerified(user.id, true)  // Verify user before authentication

            val tokenPair = service.tokenByPhone("+1234567890", "SecurePass123")
            tokenPair.access shouldBe "mock-access-token"
            tokenPair.refresh shouldBe "mock-refresh-token"
        }

        test("should throw Authorization.InvalidCredentials for wrong password") {
            val user = service.createUser("john@example.com", null, "SecurePass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()
            service.setVerified(user.id, true)

            shouldThrow<Authorization.InvalidCredentials> {
                service.tokenByEmail("john@example.com", "WrongPassword")
            }
        }

        test("should throw Authorization.InvalidCredentials for non-existent user") {
            shouldThrow<Authorization.InvalidCredentials> {
                service.tokenByEmail("nonexistent@example.com", "AnyPassword")
            }
        }
    }

    context("Password Management") {
        test("should change password successfully") {
            val user = service.createUser("john@example.com", null, "OldPass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()
            service.setVerified(user.id, true)

            service.changePassword(user.id, "OldPass123", "NewPass456")

            // Should be able to login with new password
            val tokenPair = service.tokenByEmail("john@example.com", "NewPass456")
            tokenPair.access shouldBe "mock-access-token"
        }

        test("should throw Authorization.InvalidCredentials when changing password with wrong old password") {
            val user = service.createUser("john@example.com", null, "OldPass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()

            shouldThrow<Authorization.InvalidCredentials> {
                service.changePassword(user.id, "WrongOldPass", "NewPass456")
            }
        }

        test("should reset password successfully") {
            val user = service.createUser("john@example.com", null, "OldPass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()
            service.setVerified(user.id, true)

            service.resetPassword(user.id, "NewPass456")

            // Should be able to login with new password
            val tokenPair = service.tokenByEmail("john@example.com", "NewPass456")
            tokenPair.access shouldBe "mock-access-token"

            // Old password should not work
            shouldThrow<Authorization.InvalidCredentials> {
                service.tokenByEmail("john@example.com", "OldPass123")
            }
        }
    }

    context("User Update") {
        test("should update user email") {
            val user = service.createUser("old@example.com", null, "pass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()

            val result = service.updateUser(UpdateUserFields(
                userId = user.id,
                fields = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com"))
            ))

            result shouldBe instanceOf<UpdateResult.Success>()
            service.getUser(user.id).email shouldBe "new@example.com"
        }

        test("should update user phone") {
            val user = service.createUser("john@example.com", null, "pass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()

            val result = service.updateUser(UpdateUserFields(
                userId = user.id,
                fields = UserFieldUpdates(phone = FieldUpdate.SetValue("+1234567890"))
            ))

            result shouldBe instanceOf<UpdateResult.Success>()
            service.getUser(user.id).phoneNumber shouldBe "+1234567890"
        }

        test("should update user profile") {
            // Create user with initial profile
            val initialProfile = UserProfile("John", "Doe", "123 Main St", "old-pic.jpg")
            val user = service.createUser("john@example.com", null, "pass123", emptyList(), emptyMap(), initialProfile)
            user.shouldNotBeNull()

            val newProfile = UserProfile("Jane", "Smith", "456 Oak Ave", "new-pic.jpg")

            val result = service.updateUser(UpdateProfileFields(
                userId = user.id,
                fields = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("Jane"),
                    lastName = FieldUpdate.SetValue("Smith"),
                    address = FieldUpdate.SetValue("456 Oak Ave"),
                    profilePicture = FieldUpdate.SetValue("new-pic.jpg")
                )
            ))

            result shouldBe instanceOf<UpdateResult.Success>()
            service.getUserProfile(user.id) shouldBe newProfile
        }

        test("should update custom attributes with merge mode") {
            val user = service.createUser("john@example.com", null, "pass123", emptyList(), mapOf("key1" to "value1"), null)
            user.shouldNotBeNull()

            val result = service.updateUser(UpdateAttributes(
                userId = user.id,
                changes = AttributeChanges(listOf(
                    AttributeChange.Set("key2", "value2")
                ))
            ))

            result shouldBe instanceOf<UpdateResult.Success>()
            service.getCustomAttributes(user.id) shouldContainExactly mapOf("key1" to "value1", "key2" to "value2")
        }

        test("should update custom attributes with replace mode") {
            val user = service.createUser("john@example.com", null, "pass123", emptyList(), mapOf("key1" to "value1"), null)
            user.shouldNotBeNull()

            val result = service.updateUser(UpdateAttributes(
                userId = user.id,
                changes = AttributeChanges(listOf(
                    AttributeChange.ReplaceAll(mapOf("key2" to "value2"))
                ))
            ))

            result shouldBe instanceOf<UpdateResult.Success>()
            service.getCustomAttributes(user.id) shouldContainExactly mapOf("key2" to "value2")
        }
    }

    context("User Roles") {
        test("should update user roles") {
            // Roles already seeded in beforeEach
            // Note: realm.owner role is automatically added to all users at creation
            val user = service.createUser("john@example.com", null, "pass123", listOf("USER"), emptyMap(), null)
            user.shouldNotBeNull()

            // updateUserRoles replaces all roles (doesn't preserve realm.owner automatically)
            service.updateUserRoles(user.id, listOf("ADMIN", "MODERATOR"))

            val fullUser = service.getFullUser(user.id)
            fullUser.roles.map { it.name } shouldContainExactlyInAnyOrder listOf("ADMIN", "MODERATOR")
        }

        test("should get seeded roles") {
            // Roles already seeded in beforeEach
            val roles = service.getSeededRoles()
            roles shouldContainExactlyInAnyOrder listOf(realm.owner, "USER", "ADMIN", "MODERATOR")
        }
    }

    // Token Revocation tests moved to DefaultTokenManagerTest since they depend on real token generation

    context("User Verification") {
        test("should set user as verified") {
            val user = service.createUser("john@example.com", null, "pass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()

            service.getUser(user.id).isVerified shouldBe false

            service.setVerified(user.id, true)

            service.getUser(user.id).isVerified shouldBe true
        }

        test("should unset user verification") {
            val user = service.createUser("john@example.com", null, "pass123", emptyList(), emptyMap(), null)
            user.shouldNotBeNull()
            service.setVerified(user.id, true)

            service.getUser(user.id).isVerified shouldBe true

            service.setVerified(user.id, false)

            service.getUser(user.id).isVerified shouldBe false
        }
    }
})
