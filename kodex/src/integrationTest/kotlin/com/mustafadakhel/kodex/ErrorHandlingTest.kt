package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.application.*
import io.ktor.server.testing.*

class ErrorHandlingTest : FunSpec({

    test("creating user with duplicate email throws EmailAlreadyExists") {
        testApplication {
            val realm = Realm("error-email")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-email;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "duplicate@example.com",
                    phone = null,
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val exception = shouldThrow<KodexThrowable.EmailAlreadyExists> {
                    services.users.createUser(
                        email = "duplicate@example.com",
                        phone = null,
                        password = "DifferentPass123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }
                exception.message shouldContain "Email already exists"
            }
        }
    }

    test("creating user with duplicate phone throws PhoneAlreadyExists") {
        testApplication {
            val realm = Realm("error-phone")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-phone;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "user1@example.com",
                    phone = "+1234567890",
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val exception = shouldThrow<KodexThrowable.PhoneAlreadyExists> {
                    services.users.createUser(
                        email = "user2@example.com",
                        phone = "+1234567890",
                        password = "DifferentPass123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }
                exception.message shouldContain "Phone number already exists"
            }
        }
    }

    test("login with wrong password throws InvalidCredentials") {
        testApplication {
            val realm = Realm("error-creds")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-creds;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "CorrectPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.login(
                        "user@example.com",
                        "WrongPassword123!",
                        "127.0.0.1",
                        "TestAgent"
                    )
                }
            }
        }
    }

    test("getting non-existent user throws UserNotFound") {
        testApplication {
            val realm = Realm("error-user")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-user;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
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

    test("assigning non-existent role throws RoleNotFound") {
        testApplication {
            val realm = Realm("error-role")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-role;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val exception = shouldThrow<KodexThrowable.RoleNotFound> {
                    services.users.updateUserRoles(user!!.id, listOf("nonexistent-role"))
                }
                exception.roleName shouldBe "nonexistent-role"
            }
        }
    }

    test("verifying invalid token returns null") {
        testApplication {
            val realm = Realm("error-token")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-token;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val result = services.tokens.verify("not.a.valid.jwt.token")
                result.shouldBeNull()
            }
        }
    }

    test("verifying malformed JWT returns null") {
        testApplication {
            val realm = Realm("error-jwt")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-jwt;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val malformedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
                val result = services.tokens.verify(malformedToken)
                result.shouldBeNull()
            }
        }
    }

    test("user creation with invalid role fails validation and does not persist user") {
        testApplication {
            val realm = Realm("error-rollback")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-rollback;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val exception = shouldThrow<KodexThrowable.RoleNotFound> {
                    services.users.createUser(
                        email = "rollback@example.com",
                        phone = null,
                        password = "Password123!",
                        roleNames = listOf("nonexistent-role"),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }

                exception.roleName shouldBe "nonexistent-role"

                shouldThrow<KodexThrowable.UserNotFound> {
                    services.users.getUserByEmail("rollback@example.com")
                }
            }
        }
    }

    test("exception messages do not expose internal database details") {
        testApplication {
            val realm = Realm("error-sanitize")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-sanitize;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                services.users.createUser(
                    email = "original@example.com",
                    phone = null,
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                val exception = shouldThrow<KodexThrowable.EmailAlreadyExists> {
                    services.users.createUser(
                        email = "original@example.com",
                        phone = null,
                        password = "DifferentPass123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }

                val message = exception.message ?: ""
                message shouldNotContain "SELECT"
                message shouldNotContain "INSERT"
                message shouldNotContain "Users"
                message shouldNotContain "H2"
                message shouldNotContain "constraint"
            }
        }
    }

    test("assigning same role twice is idempotent") {
        testApplication {
            val realm = Realm("error-idempotent")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-idempotent;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        roles {
                            role("test-role")
                        }
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "idempotent@example.com",
                    phone = null,
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val initialRoles = services.users.getFullUser(user!!.id).roles.map { it.name }

                shouldNotThrow<Exception> {
                    services.users.updateUserRoles(user.id, initialRoles + "test-role")
                }

                shouldNotThrow<Exception> {
                    services.users.updateUserRoles(user.id, initialRoles + "test-role")
                }

                val finalRoles = services.users.getFullUser(user.id).roles.map { it.name }
                finalRoles.count { it == "test-role" } shouldBe 1
            }
        }
    }

    test("token verification is idempotent") {
        testApplication {
            val realm = Realm("error-verify-idempotent")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-verify-idempotent;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "verify@example.com",
                    phone = null,
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val tokens = services.auth.login(
                    "verify@example.com",
                    "Password123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                val result1 = services.tokens.verify(tokens.access)
                val result2 = services.tokens.verify(tokens.access)
                val result3 = services.tokens.verify(tokens.access)

                result1.shouldNotBeNull()
                result2.shouldNotBeNull()
                result3.shouldNotBeNull()
                result1.userId shouldBe result2.userId
                result2.userId shouldBe result3.userId
            }
        }
    }

    test("login with non-existent email throws InvalidCredentials") {
        testApplication {
            val realm = Realm("error-nonexistent")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-nonexistent;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.login(
                        "nonexistent@example.com",
                        "SomePassword123!",
                        "127.0.0.1",
                        "TestAgent"
                    )
                }
            }
        }
    }

    test("validation errors provide helpful messages") {
        testApplication {
            val realm = Realm("error-validation")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-validation;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val exception = shouldThrow<KodexThrowable.RoleNotFound> {
                    services.users.createUser(
                        email = "validation@example.com",
                        phone = null,
                        password = "Password123!",
                        roleNames = listOf("missing-role"),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }

                exception.message shouldContain "missing-role"
                exception.roleName shouldBe "missing-role"
            }
        }
    }

    test("unique constraint violation produces meaningful error") {
        testApplication {
            val realm = Realm("error-constraint")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:error-constraint;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("error-handling-secret-32-chars!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                services.users.createUser(
                    email = "unique@example.com",
                    phone = "+1111111111",
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                shouldThrow<KodexThrowable.EmailAlreadyExists> {
                    services.users.createUser(
                        email = "unique@example.com",
                        phone = "+2222222222",
                        password = "Password123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }

                shouldThrow<KodexThrowable.PhoneAlreadyExists> {
                    services.users.createUser(
                        email = "different@example.com",
                        phone = "+1111111111",
                        password = "Password123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )
                }
            }
        }
    }
})
