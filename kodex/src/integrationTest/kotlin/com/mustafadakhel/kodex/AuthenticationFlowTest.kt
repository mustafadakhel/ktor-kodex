package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

class AuthenticationFlowTest : FunSpec({

    context("End-to-End Authentication Flow") {
        test("should reject access to protected route without token") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:auth-flow-test-1;DB_CLOSE_DELAY=-1;"
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

                    routing {
                        authenticateFor(realm) {
                            get("/protected") {
                                call.respondText("Protected content")
                            }
                        }
                    }
                }

                val response = client.get("/protected")
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("should allow access to public route without token") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:auth-flow-test-2;DB_CLOSE_DELAY=-1;"
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

                    routing {
                        get("/public") {
                            call.respondText("Public content")
                        }
                    }
                }

                val response = client.get("/public")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Public content"
            }
        }

        test("should complete full authentication and access flow") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:auth-flow-test-3;DB_CLOSE_DELAY=-1;"
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

                    routing {
                        authenticateFor(realm) {
                            get("/protected") {
                                call.respondText("Protected content")
                            }
                        }
                    }

                    val services = kodex.servicesOf(realm)

                    val user = services.users.createUser(
                        email = "test@example.com",
                        phone = null,
                        password = "SecurePass123",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    user.shouldNotBeNull()
                    user.email shouldBe "test@example.com"

                    val tokens = services.auth.login("test@example.com", "SecurePass123", "192.168.1.1", "TestAgent")
                    tokens.shouldNotBeNull()
                }
            }
        }

        test("should fail authentication with wrong password") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:auth-flow-test-4;DB_CLOSE_DELAY=-1;"
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
                        email = "wrongpass@example.com",
                        phone = null,
                        password = "CorrectPass123",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    val result = runCatching {
                        services.auth.login("wrongpass@example.com", "WrongPassword", "192.168.1.1", "TestAgent")
                    }

                    result.isFailure shouldBe true
                }
            }
        }

        test("should refresh tokens successfully") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:auth-flow-test-5;DB_CLOSE_DELAY=-1;"
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
                        email = "refresh@example.com",
                        phone = null,
                        password = "SecurePass123",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    val originalTokens = services.auth.login("refresh@example.com", "SecurePass123", "192.168.1.1", "TestAgent")
                    val refreshedTokens = services.tokens.refresh(user.id, originalTokens.refresh)

                    refreshedTokens.shouldNotBeNull()
                    refreshedTokens.access.shouldNotBeNull()
                    refreshedTokens.refresh.shouldNotBeNull()
                }
            }
        }
    }

    context("Registration Tests") {
        test("successful registration with valid email and password") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-register-1;DB_CLOSE_DELAY=-1;"
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
                        email = "newuser@example.com",
                        phone = null,
                        password = "SecurePassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    user.shouldNotBeNull()
                    user.email shouldBe "newuser@example.com"
                    user.phoneNumber.shouldBeNull()
                }
            }
        }

        test("successful registration with phone number") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-register-2;DB_CLOSE_DELAY=-1;"
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
                        email = "phoneuser@example.com",
                        phone = "+1234567890",
                        password = "SecurePassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    user.shouldNotBeNull()
                    user.phoneNumber shouldBe "+1234567890"
                }
            }
        }

        test("registration fails with duplicate email") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-register-3;DB_CLOSE_DELAY=-1;"
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
                        email = "duplicate@example.com",
                        phone = null,
                        password = "Password123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    val exception = shouldThrow<KodexThrowable.EmailAlreadyExists> {
                        services.users.createUser(
                            email = "duplicate@example.com",
                            phone = null,
                            password = "DifferentPassword123!",
                            roleNames = emptyList(),
                            customAttributes = emptyMap(),
                            profile = null
                        )
                    }

                    exception.message shouldContain "Email already exists"
                }
            }
        }

    }

    context("Login Tests") {
        test("successful login with email returns access and refresh tokens") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-login-1;DB_CLOSE_DELAY=-1;"
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
                        email = "loginuser@example.com",
                        phone = null,
                        password = "LoginPassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    val ipAddress = "192.168.1.100"
                    val userAgent = "TestBrowser/1.0"

                    val tokens = services.auth.login(
                        "loginuser@example.com",
                        "LoginPassword123!",
                        ipAddress,
                        userAgent
                    )

                    tokens.shouldNotBeNull()
                    tokens.access.shouldNotBeNull()
                    tokens.refresh.shouldNotBeNull()
                }
            }
        }

        test("successful login by phone number") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-login-2;DB_CLOSE_DELAY=-1;"
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
                        email = "phonelogin@example.com",
                        phone = "+1234567890",
                        password = "PhonePassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    val tokens = services.auth.loginByPhone(
                        "+1234567890",
                        "PhonePassword123!",
                        "192.168.1.100",
                        "TestBrowser/1.0"
                    )

                    tokens.shouldNotBeNull()
                    tokens.access.shouldNotBeNull()
                    tokens.refresh.shouldNotBeNull()
                }
            }
        }

        test("login fails with invalid credentials") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-login-3;DB_CLOSE_DELAY=-1;"
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
                        email = "invalidcreds@example.com",
                        phone = null,
                        password = "CorrectPassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    val exception = shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                        services.auth.login(
                            "invalidcreds@example.com",
                            "WrongPassword123!",
                            "192.168.1.100",
                            "TestBrowser/1.0"
                        )
                    }

                    exception.message shouldContain "Invalid credentials"
                }
            }
        }

        test("login tracks device information") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-login-4;DB_CLOSE_DELAY=-1;"
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
                        email = "devicetrack@example.com",
                        phone = null,
                        password = "DevicePassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    val ipAddress = "203.0.113.45"
                    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

                    val tokens = services.auth.login(
                        "devicetrack@example.com",
                        "DevicePassword123!",
                        ipAddress,
                        userAgent
                    )

                    tokens.shouldNotBeNull()
                    tokens.access.shouldNotBeNull()
                    tokens.refresh.shouldNotBeNull()
                }
            }
        }
    }

    context("Token Refresh Tests") {
        test("successful token refresh returns new tokens") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-refresh-1;DB_CLOSE_DELAY=-1;"
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
                        email = "refreshuser@example.com",
                        phone = null,
                        password = "RefreshPassword123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    val originalTokens = services.auth.login(
                        "refreshuser@example.com",
                        "RefreshPassword123!",
                        "192.168.1.100",
                        "TestBrowser/1.0"
                    )

                    val ipAddress = "192.168.1.100"
                    val userAgent = "TestBrowser/1.0"

                    val newTokens = services.tokens.refresh(
                        user.id,
                        originalTokens.refresh,
                        ipAddress,
                        userAgent
                    )

                    newTokens.shouldNotBeNull()
                    newTokens.access.shouldNotBeNull()
                    newTokens.refresh.shouldNotBeNull()
                    newTokens.access shouldNotBe originalTokens.access
                    newTokens.refresh shouldNotBe originalTokens.refresh
                }
            }
        }

        test("token refresh tracks device changes") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-refresh-2;DB_CLOSE_DELAY=-1;"
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
                        email = "devicechange@example.com",
                        phone = null,
                        password = "DeviceChange123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    val originalTokens = services.auth.login(
                        "devicechange@example.com",
                        "DeviceChange123!",
                        "192.168.1.100",
                        "Browser/1.0"
                    )

                    val newTokens = services.tokens.refresh(
                        user.id,
                        originalTokens.refresh,
                        "203.0.113.50",
                        "Mobile/2.0"
                    )

                    newTokens.shouldNotBeNull()
                }
            }
        }

        test("token refresh fails with invalid refresh token") {
            testApplication {
                val realm = Realm("test-realm")

                application {
                    val kodex = install(Kodex) {
                        database {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl = "jdbc:h2:mem:test-refresh-3;DB_CLOSE_DELAY=-1;"
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
                        email = "invalidrefresh@example.com",
                        phone = null,
                        password = "InvalidRefresh123!",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    val exception = shouldThrow<Throwable> {
                        services.tokens.refresh(
                            user.id,
                            "invalid-refresh-token-12345",
                            "192.168.1.100",
                            "TestBrowser/1.0"
                        )
                    }

                    exception.shouldNotBeNull()
                }
            }
        }
    }

})
