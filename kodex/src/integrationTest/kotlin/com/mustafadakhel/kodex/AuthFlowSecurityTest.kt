package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlin.math.abs
import kotlin.time.measureTime

/**
 * Security tests for authentication flow.
 * Tests constant-time response for timing attack prevention.
 */
class AuthFlowSecurityTest : FunSpec({

    test("login should take similar time for existing vs non-existing user (timing attack prevention)") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:timing-attack-test;DB_CLOSE_DELAY=-1;"
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

                // Create a user that exists
                services.users.createUser(
                    email = "existing@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Warmup - run a few attempts first to warm up JIT
                repeat(3) {
                    runCatching { services.auth.login("warmup$it@example.com", "wrong", "127.0.0.1", "Agent") }
                }

                // Measure time for existing user with wrong password
                val timesExisting = mutableListOf<Long>()
                repeat(5) {
                    val duration = measureTime {
                        runCatching {
                            services.auth.login("existing@example.com", "wrongpassword$it", "127.0.0.1", "TestAgent")
                        }
                    }
                    timesExisting.add(duration.inWholeMilliseconds)
                }

                // Measure time for non-existing user
                val timesNonExisting = mutableListOf<Long>()
                repeat(5) {
                    val duration = measureTime {
                        runCatching {
                            services.auth.login("nonexistent$it@example.com", "anypassword", "127.0.0.1", "TestAgent")
                        }
                    }
                    timesNonExisting.add(duration.inWholeMilliseconds)
                }

                // Calculate averages (excluding first measurement which may have more variance)
                val avgExisting = timesExisting.drop(1).average()
                val avgNonExisting = timesNonExisting.drop(1).average()

                // The times should be within 100ms of each other for constant-time behavior
                // This tolerance accounts for test environment variations
                val difference = abs(avgExisting - avgNonExisting)
                difference.toLong() shouldBeLessThan 100L
            }
        }
    }

    test("login for existing user with wrong password should throw InvalidCredentials") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:invalid-creds-existing;DB_CLOSE_DELAY=-1;"
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

                services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "CorrectPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.login("user@example.com", "WrongPassword", "127.0.0.1", "TestAgent")
                }
            }
        }
    }

    test("login for non-existing user should throw InvalidCredentials (not UserNotFound)") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:invalid-creds-nonexisting;DB_CLOSE_DELAY=-1;"
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

                // User does not exist - should still throw InvalidCredentials (not UserNotFound)
                // This prevents user enumeration attacks
                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.login("doesnotexist@example.com", "anypassword", "127.0.0.1", "TestAgent")
                }
            }
        }
    }

    test("password change should work with correct old password") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:password-change-test;DB_CLOSE_DELAY=-1;"
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
                    email = "pwchange@example.com",
                    phone = null,
                    password = "OldPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Change password
                services.auth.changePassword(user.id, "OldPassword123!", "NewPassword456!")

                // Old password should no longer work
                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.login("pwchange@example.com", "OldPassword123!", "127.0.0.1", "TestAgent")
                }

                // New password should work
                val tokens = services.auth.login("pwchange@example.com", "NewPassword456!", "127.0.0.1", "TestAgent")
                tokens.shouldNotBeNull()
                tokens.access.shouldNotBeNull()
            }
        }
    }

    test("password change should fail with wrong old password") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:password-change-fail;DB_CLOSE_DELAY=-1;"
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
                    email = "pwchangefail@example.com",
                    phone = null,
                    password = "CorrectOldPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Try to change password with wrong old password
                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.changePassword(user.id, "WrongOldPassword!", "NewPassword456!")
                }

                // Original password should still work
                val tokens = services.auth.login("pwchangefail@example.com", "CorrectOldPassword123!", "127.0.0.1", "TestAgent")
                tokens.shouldNotBeNull()
            }
        }
    }

    test("admin password reset should work without knowing old password") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:admin-reset-test;DB_CLOSE_DELAY=-1;"
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
                    email = "adminreset@example.com",
                    phone = null,
                    password = "OldUserPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Admin resets password without knowing old password
                services.auth.resetPassword(user.id, "AdminResetPassword789!")

                // Old password should no longer work
                shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                    services.auth.login("adminreset@example.com", "OldUserPassword123!", "127.0.0.1", "TestAgent")
                }

                // New password should work
                val tokens = services.auth.login("adminreset@example.com", "AdminResetPassword789!", "127.0.0.1", "TestAgent")
                tokens.shouldNotBeNull()
                tokens.access.shouldNotBeNull()
            }
        }
    }

    test("user can have multiple active login sessions from different devices") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:multi-session-test;DB_CLOSE_DELAY=-1;"
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

                services.users.createUser(
                    email = "multisession@example.com",
                    phone = null,
                    password = "MultiSession123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                // Login from device 1
                val tokens1 = services.auth.login(
                    "multisession@example.com",
                    "MultiSession123!",
                    "192.168.1.1",
                    "Chrome/120 Windows"
                )

                // Login from device 2
                val tokens2 = services.auth.login(
                    "multisession@example.com",
                    "MultiSession123!",
                    "192.168.1.2",
                    "Safari/17 macOS"
                )

                // Login from device 3
                val tokens3 = services.auth.login(
                    "multisession@example.com",
                    "MultiSession123!",
                    "10.0.0.1",
                    "Firefox/121 Linux"
                )

                // All tokens should be valid and different
                tokens1.access.shouldNotBeNull()
                tokens2.access.shouldNotBeNull()
                tokens3.access.shouldNotBeNull()

                // Each device should have a unique token
                tokens1.access shouldBe tokens1.access // self-equality check
                (tokens1.access != tokens2.access) shouldBe true
                (tokens2.access != tokens3.access) shouldBe true
                (tokens1.access != tokens3.access) shouldBe true
            }
        }
    }

    test("access token should contain expected claims") {
        testApplication {
            val realm = Realm("test-realm")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:token-claims-test;DB_CLOSE_DELAY=-1;"
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
                    email = "claims@example.com",
                    phone = null,
                    password = "ClaimsTest123!",
                    roleNames = emptyList(),  // Just use default roles
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val tokens = services.auth.login(
                    "claims@example.com",
                    "ClaimsTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                // Verify the token can be used (implicitly validates signature and claims)
                val principal = services.tokens.verify(tokens.access)
                principal.shouldNotBeNull()
                principal.userId shouldBe user.id
                principal.realm.owner shouldBe "test-realm"
                // Roles should include realm owner role (auto-added with name = realm.owner)
                principal.roles.any { it.name == "test-realm" } shouldBe true
            }
        }
    }
})
