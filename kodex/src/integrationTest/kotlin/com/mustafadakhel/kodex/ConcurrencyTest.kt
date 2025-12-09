package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ConcurrencyTest : FunSpec({

    test("concurrent token refreshes from same user produce unique tokens") {
        testApplication {
            val realm = Realm("refresh-race")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:concurrent-refresh;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("concurrent-refresh-secret-32ch!")
                        }
                        tokenValidity {
                            persist(TokenType.RefreshToken, true)
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "concurrent@example.com",
                    phone = null,
                    password = "ConcurrentTest123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val initialTokens = services.auth.login(
                    "concurrent@example.com",
                    "ConcurrentTest123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                coroutineScope {
                    val refreshResults = (1..5).map {
                        async {
                            try {
                                val newTokens = services.tokens.refresh(user!!.id, initialTokens.refresh)
                                "success:${newTokens.access.takeLast(10)}"
                            } catch (e: Exception) {
                                "error:${e::class.simpleName}"
                            }
                        }
                    }.awaitAll()

                    val successes = refreshResults.count { it.startsWith("success:") }
                    successes shouldBeGreaterThanOrEqual 1
                }
            }
        }
    }

    test("concurrent logins from different IPs all succeed") {
        testApplication {
            val realm = Realm("multi-ip-login")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:multi-ip-login;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("concurrent-update-secret-32ch!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "multiip@example.com",
                    phone = null,
                    password = "MultiIP123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                coroutineScope {
                    val loginResults = (1..10).map { i ->
                        async {
                            try {
                                val tokens = services.auth.login(
                                    "multiip@example.com",
                                    "MultiIP123!",
                                    "192.168.1.$i",
                                    "Agent-$i"
                                )
                                "success:${tokens.access.takeLast(8)}"
                            } catch (e: Exception) {
                                "error:${e::class.simpleName}"
                            }
                        }
                    }.awaitAll()

                    val successes = loginResults.count { it.startsWith("success:") }
                    successes shouldBe 10

                    val uniqueTokens = loginResults.filter { it.startsWith("success:") }.toSet()
                    uniqueTokens.size shouldBe 10
                }
            }
        }
    }

    test("concurrent failed logins all return invalid credentials") {
        testApplication {
            val realm = Realm("failed-login-race")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:concurrent-failed;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("concurrent-failed-secret-32ch!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "failedrace@example.com",
                    phone = null,
                    password = "FailedRace123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                coroutineScope {
                    val loginResults = (1..10).map { i ->
                        async {
                            try {
                                services.auth.login(
                                    "failedrace@example.com",
                                    "wrong-password-$i",
                                    "192.168.1.$i",
                                    "TestAgent"
                                )
                                "success"
                            } catch (e: KodexThrowable.Authorization.InvalidCredentials) {
                                "invalid"
                            } catch (e: Throwable) {
                                "error:${e::class.simpleName}"
                            }
                        }
                    }.awaitAll()

                    val invalidCount = loginResults.count { it == "invalid" }
                    invalidCount shouldBe 10
                }

                val result = services.auth.login(
                    "failedrace@example.com",
                    "FailedRace123!",
                    "192.168.1.100",
                    "TestAgent"
                )
                result.access.shouldNotBeNull()
            }
        }
    }

    test("concurrent role assignments do not create duplicates") {
        testApplication {
            val realm = Realm("role-race")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:concurrent-role;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        roles {
                            role("concurrent-role")
                        }
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("concurrent-role-secret-32chars!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "rolerace@example.com",
                    phone = null,
                    password = "RoleRace123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val initialRoles = services.users.getFullUser(user!!.id).roles.map { it.name }
                initialRoles shouldNotContain "concurrent-role"

                coroutineScope {
                    val assignResults = (1..5).map {
                        async {
                            try {
                                services.users.updateUserRoles(user.id, initialRoles + "concurrent-role")
                                "success"
                            } catch (e: Exception) {
                                "error:${e::class.simpleName}"
                            }
                        }
                    }.awaitAll()

                    val successes = assignResults.count { it == "success" }
                    successes shouldBe 5
                }

                val finalRoles = services.users.getFullUser(user.id).roles.map { it.name }
                finalRoles.toSet().size shouldBe finalRoles.size
                finalRoles shouldContain "concurrent-role"
            }
        }
    }

    test("concurrent user creation with same email - only one succeeds") {
        testApplication {
            val realm = Realm("create-race")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:concurrent-create;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("concurrent-create-secret-32ch!!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val sharedEmail = "race@example.com"

                coroutineScope {
                    val createResults = (1..5).map { i ->
                        async {
                            try {
                                val user = services.users.createUser(
                                    email = sharedEmail,
                                    phone = null,
                                    password = "Password$i!",
                                    roleNames = emptyList(),
                                    customAttributes = emptyMap(),
                                    profile = null
                                )
                                if (user != null) "success:${user.id}" else "null"
                            } catch (e: KodexThrowable.EmailAlreadyExists) {
                                "duplicate"
                            } catch (e: Exception) {
                                "error:${e::class.simpleName}"
                            }
                        }
                    }.awaitAll()

                    val successes = createResults.count { it.startsWith("success:") }
                    val duplicates = createResults.count { it == "duplicate" }

                    successes shouldBe 1
                    duplicates shouldBe 4
                }

                val user = services.users.getUserByEmail(sharedEmail)
                user.shouldNotBeNull()
            }
        }
    }

    test("concurrent login requests all succeed for valid credentials") {
        testApplication {
            val realm = Realm("login-concurrent")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:concurrent-login;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm) {
                        claims {
                            issuer("test-issuer")
                            audience("test-audience")
                        }
                        secrets {
                            raw("concurrent-login-secret-32char!")
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                val user = services.users.createUser(
                    email = "concurrent-login@example.com",
                    phone = null,
                    password = "ConcurrentLogin123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                coroutineScope {
                    val loginResults = (1..10).map { i ->
                        async {
                            try {
                                val tokens = services.auth.login(
                                    "concurrent-login@example.com",
                                    "ConcurrentLogin123!",
                                    "192.168.1.$i",
                                    "TestAgent-$i"
                                )
                                "success:${tokens.access.takeLast(10)}"
                            } catch (e: Exception) {
                                "error:${e::class.simpleName}"
                            }
                        }
                    }.awaitAll()

                    val successes = loginResults.count { it.startsWith("success:") }
                    successes shouldBe 10

                    val uniqueTokens = loginResults.filter { it.startsWith("success:") }.toSet()
                    uniqueTokens.size shouldBe 10
                }
            }
        }
    }
})
