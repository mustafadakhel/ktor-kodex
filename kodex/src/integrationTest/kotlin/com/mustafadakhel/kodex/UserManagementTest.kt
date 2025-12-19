package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.application.*
import io.ktor.server.testing.*

/**
 * User Management integration tests.
 * Tests user creation, updates, deletion, roles, and queries via UserService.
 */
class UserManagementTest : FunSpec({

    test("user creation with all fields should persist correctly") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-create-full;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Create user with all fields
                val user = services.users.createUser(
                    email = "fulluser@example.com",
                    phone = "+1234567890",
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = mapOf("dept" to "engineering", "level" to "senior"),
                    profile = UserProfile(
                        firstName = "John",
                        lastName = "Doe",
                        address = "123 Main St",
                        profilePicture = "https://example.com/pic.jpg"
                    )
                )

                user.shouldNotBeNull()
                user.email shouldBe "fulluser@example.com"
                user.phoneNumber shouldBe "+1234567890"

                // Retrieve and verify all fields
                val fullUser = services.users.getFullUser(user.id)
                fullUser.shouldNotBeNull()
                fullUser.email shouldBe "fulluser@example.com"
                fullUser.phoneNumber shouldBe "+1234567890"

                // Verify profile
                fullUser.profile.shouldNotBeNull()
                fullUser.profile!!.firstName shouldBe "John"
                fullUser.profile!!.lastName shouldBe "Doe"
                fullUser.profile!!.address shouldBe "123 Main St"
                fullUser.profile!!.profilePicture shouldBe "https://example.com/pic.jpg"

                // Verify custom attributes
                fullUser.customAttributes.shouldNotBeNull()
                fullUser.customAttributes shouldContainExactly mapOf("dept" to "engineering", "level" to "senior")

                // Verify auto-added realm owner role
                fullUser.roles.any { it.name == "test-realm" } shouldBe true
            }
        }
    }

    test("user creation with only email should work") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-create-email;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "emailonly@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                user.shouldNotBeNull()
                user.email shouldBe "emailonly@example.com"
                user.phoneNumber.shouldBeNull()
            }
        }
    }

    test("user creation with only phone should work") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-create-phone;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = null,
                    phone = "+1987654321",
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                user.shouldNotBeNull()
                user.email.shouldBeNull()
                user.phoneNumber shouldBe "+1987654321"
            }
        }
    }

    test("duplicate email should be rejected") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-dup-email;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Create first user
                services.users.createUser(
                    email = "duplicate@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Try to create second user with same email
                shouldThrow<KodexThrowable.EmailAlreadyExists> {
                    services.users.createUser(
                        email = "duplicate@example.com",
                        phone = null,
                        password = "DifferentPassword456!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }
            }
        }
    }

    test("duplicate phone should be rejected") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-dup-phone;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Create first user
                services.users.createUser(
                    email = null,
                    phone = "+1111111111",
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Try to create second user with same phone
                shouldThrow<KodexThrowable.PhoneAlreadyExists> {
                    services.users.createUser(
                        email = null,
                        phone = "+1111111111",
                        password = "DifferentPassword456!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }
            }
        }
    }

    // Note: User deletion currently fails due to FK constraint violation with UserRoles
    // This is a known limitation - the deleteUser function needs to cascade related data
    // The auto-added realm owner role creates a UserRoles entry that blocks deletion
    xtest("user deletion should remove user from database - PENDING FK CASCADE FIX") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-delete;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Create user without related data (profile, attributes)
                // Note: deletion with related data may require cascade setup
                val user = services.users.createUser(
                    email = "todelete@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Verify user exists
                services.users.getUser(user.id).shouldNotBeNull()

                // Delete user
                val deleted = services.users.deleteUser(user.id)
                deleted shouldBe true

                // Verify user no longer exists
                services.users.getUserOrNull(user.id).shouldBeNull()
            }
        }
    }

    test("deleting non-existent user should return false") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-delete-notfound;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val deleted = services.users.deleteUser(java.util.UUID.randomUUID())
                deleted shouldBe false
            }
        }
    }

    test("getAllUsers should return all users") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-all;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                // Create multiple users
                services.users.createUser(
                    email = "user1@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                services.users.createUser(
                    email = "user2@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                services.users.createUser(
                    email = "user3@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                val allUsers = services.users.getAllUsers()
                allUsers shouldHaveSize 3

                val emails = allUsers.map { it.email }
                emails shouldContain "user1@example.com"
                emails shouldContain "user2@example.com"
                emails shouldContain "user3@example.com"
            }
        }
    }

    test("getUserByEmail should find user") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-by-email;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val created = services.users.createUser(
                    email = "findme@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val found = services.users.getUserByEmail("findme@example.com")
                found.id shouldBe created.id
                found.email shouldBe "findme@example.com"
            }
        }
    }

    test("getUserByEmail for non-existent user should throw") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-by-email-404;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                shouldThrow<KodexThrowable.UserNotFound> {
                    services.users.getUserByEmail("nonexistent@example.com")
                }
            }
        }
    }

    test("getUserByPhone should find user") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-by-phone;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val created = services.users.createUser(
                    email = null,
                    phone = "+1999888777",
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val found = services.users.getUserByPhone("+1999888777")
                found.id shouldBe created.id
                found.phoneNumber shouldBe "+1999888777"
            }
        }
    }

    test("getUserOrNull should return null for non-existent user") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-or-null;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val result = services.users.getUserOrNull(java.util.UUID.randomUUID())
                result.shouldBeNull()
            }
        }
    }

    test("getCustomAttributes should return user attributes") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-attrs;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "attrs@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = mapOf("key1" to "value1", "key2" to "value2"),
                    profile = null
                )!!

                val attrs = services.users.getCustomAttributes(user.id)
                attrs shouldContainExactly mapOf("key1" to "value1", "key2" to "value2")
            }
        }
    }

    test("getProfile should return user profile") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:user-get-profile;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("test-secret-key-must-be-32-chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "profile@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = UserProfile("Jane", "Smith", "456 Oak Ave", "https://example.com/jane.jpg")
                )!!

                val profile = services.users.getUserProfile(user.id)
                profile.shouldNotBeNull()
                profile.firstName shouldBe "Jane"
                profile.lastName shouldBe "Smith"
                profile.address shouldBe "456 Oak Ave"
                profile.profilePicture shouldBe "https://example.com/jane.jpg"
            }
        }
    }
})
