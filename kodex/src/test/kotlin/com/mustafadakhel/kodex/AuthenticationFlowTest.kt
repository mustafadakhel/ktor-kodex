package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

                    val service = kodex.serviceOf(realm)

                    val user = service.createUser(
                        email = "test@example.com",
                        phone = null,
                        password = "SecurePass123",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )

                    user.shouldNotBeNull()
                    user.email shouldBe "test@example.com"
                    user.isVerified shouldBe false

                    service.setVerified(user.id, true)

                    val tokens = service.tokenByEmail("test@example.com", "SecurePass123")
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

                    val service = kodex.serviceOf(realm)

                    val user = service.createUser(
                        email = "wrongpass@example.com",
                        phone = null,
                        password = "CorrectPass123",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    service.setVerified(user.id, true)

                    val result = runCatching {
                        service.tokenByEmail("wrongpass@example.com", "WrongPassword")
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

                    val service = kodex.serviceOf(realm)

                    val user = service.createUser(
                        email = "refresh@example.com",
                        phone = null,
                        password = "SecurePass123",
                        roleNames = emptyList(),
                        customAttributes = emptyMap(),
                        profile = null
                    )!!

                    service.setVerified(user.id, true)

                    val originalTokens = service.tokenByEmail("refresh@example.com", "SecurePass123")
                    val refreshedTokens = service.refresh(user.id, originalTokens.refresh)

                    refreshedTokens.shouldNotBeNull()
                    refreshedTokens.access.shouldNotBeNull()
                    refreshedTokens.refresh.shouldNotBeNull()
                }
            }
        }
    }
})
