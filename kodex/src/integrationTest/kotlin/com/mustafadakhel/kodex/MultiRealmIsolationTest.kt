package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlin.time.Duration.Companion.hours

class MultiRealmIsolationTest : FunSpec({

    test("token from one realm cannot be verified in another realm") {
        testApplication {
            val realmA = Realm("realm-a")
            val realmB = Realm("realm-b")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:cross-realm-token;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realmA) {
                        claims {
                            issuer("realm-a-issuer")
                            audience("realm-a-audience")
                        }
                        secrets {
                            raw("realm-a-secret-key-32-chars!!!")
                        }
                    }
                    realm(realmB) {
                        claims {
                            issuer("realm-b-issuer")
                            audience("realm-b-audience")
                        }
                        secrets {
                            raw("realm-b-secret-key-32-chars!!!")
                        }
                    }
                }

                val servicesA = kodex.servicesOf(realmA)
                val servicesB = kodex.servicesOf(realmB)

                val userA = servicesA.users.createUser(
                    email = "usera@example.com",
                    phone = null,
                    password = "UserAPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                userA.shouldNotBeNull()

                val tokensA = servicesA.auth.login(
                    "usera@example.com",
                    "UserAPassword123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                val principalA = servicesA.tokens.verify(tokensA.access)
                principalA.shouldNotBeNull()
                principalA.realm shouldBe realmA

                val principalB = servicesB.tokens.verify(tokensA.access)
                principalB.shouldBeNull()
            }
        }
    }

    test("each realm signs tokens with its own secret") {
        testApplication {
            val realm1 = Realm("secret-realm-1")
            val realm2 = Realm("secret-realm-2")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:realm-secrets;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realm1) {
                        claims {
                            issuer("issuer")
                            audience("audience")
                        }
                        secrets {
                            raw("first-realm-secret-32-chars!!!")
                        }
                    }
                    realm(realm2) {
                        claims {
                            issuer("issuer")
                            audience("audience")
                        }
                        secrets {
                            raw("second-realm-secret-32-chars!!")
                        }
                    }
                }

                val services1 = kodex.servicesOf(realm1)
                val services2 = kodex.servicesOf(realm2)

                val user = services1.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "Password123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                user.shouldNotBeNull()

                val tokens1 = services1.auth.login(
                    "user@example.com",
                    "Password123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                services1.tokens.verify(tokens1.access).shouldNotBeNull()
                services2.tokens.verify(tokens1.access).shouldBeNull()
            }
        }
    }

    test("roles from all realms are seeded globally") {
        testApplication {
            val tenantA = Realm("tenant-a")
            val tenantB = Realm("tenant-b")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:realm-roles;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(tenantA) {
                        roles {
                            role("manager")
                            role("analyst")
                        }
                        claims {
                            issuer("tenant-a-issuer")
                            audience("tenant-a-audience")
                        }
                        secrets {
                            raw("tenant-a-secret-key-32-chars!!")
                        }
                    }
                    realm(tenantB) {
                        roles {
                            role("supervisor")
                            role("operator")
                        }
                        claims {
                            issuer("tenant-b-issuer")
                            audience("tenant-b-audience")
                        }
                        secrets {
                            raw("tenant-b-secret-key-32-chars!!")
                        }
                    }
                }

                val servicesA = kodex.servicesOf(tenantA)

                val seededRoles = servicesA.users.getSeededRoles()
                seededRoles shouldContainExactlyInAnyOrder listOf(
                    "tenant-a", "tenant-b", "manager", "analyst", "supervisor", "operator"
                )
            }
        }
    }

    test("each realm has independent token configuration") {
        testApplication {
            val highSecurityRealm = Realm("high-security")
            val lowFrictionRealm = Realm("low-friction")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:realm-config;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(highSecurityRealm) {
                        claims {
                            issuer("high-security-issuer")
                            audience("high-security-audience")
                        }
                        secrets {
                            raw("high-security-secret-32-chars!!")
                        }
                        tokenValidity {
                            access(5.hours)
                        }
                    }
                    realm(lowFrictionRealm) {
                        claims {
                            issuer("low-friction-issuer")
                            audience("low-friction-audience")
                        }
                        secrets {
                            raw("low-friction-secret-32-chars!!!")
                        }
                        tokenValidity {
                            access(24.hours)
                        }
                    }
                }

                val highSecurityServices = kodex.servicesOf(highSecurityRealm)
                val lowFrictionServices = kodex.servicesOf(lowFrictionRealm)

                // Create user in high-security realm
                val highSecUser = highSecurityServices.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                highSecUser.shouldNotBeNull()

                // Create user with same email in low-friction realm (realm-scoped users)
                val lowFricUser = lowFrictionServices.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "SecurePassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                lowFricUser.shouldNotBeNull()

                val highSecurityTokens = highSecurityServices.auth.login(
                    "user@example.com",
                    "SecurePassword123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                val lowFrictionTokens = lowFrictionServices.auth.login(
                    "user@example.com",
                    "SecurePassword123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                highSecurityTokens.access shouldNotBe lowFrictionTokens.access

                highSecurityServices.tokens.verify(highSecurityTokens.access).shouldNotBeNull()
                highSecurityServices.tokens.verify(lowFrictionTokens.access).shouldBeNull()

                lowFrictionServices.tokens.verify(lowFrictionTokens.access).shouldNotBeNull()
                lowFrictionServices.tokens.verify(highSecurityTokens.access).shouldBeNull()
            }
        }
    }

    test("token refresh verifies in same realm only") {
        testApplication {
            val realmP = Realm("realm-p")
            val realmQ = Realm("realm-q")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:realm-refresh;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realmP) {
                        claims {
                            issuer("p-issuer")
                            audience("p-audience")
                        }
                        secrets {
                            raw("realm-p-secret-key-32-chars!!!!")
                        }
                        tokenValidity {
                            persist(TokenType.RefreshToken, true)
                        }
                    }
                    realm(realmQ) {
                        claims {
                            issuer("q-issuer")
                            audience("q-audience")
                        }
                        secrets {
                            raw("realm-q-secret-key-32-chars!!!!")
                        }
                        tokenValidity {
                            persist(TokenType.RefreshToken, true)
                        }
                    }
                }

                val servicesP = kodex.servicesOf(realmP)
                val servicesQ = kodex.servicesOf(realmQ)

                val userP = servicesP.users.createUser(
                    email = "userp@example.com",
                    phone = null,
                    password = "PasswordP123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                userP.shouldNotBeNull()

                val tokensP = servicesP.auth.login(
                    "userp@example.com",
                    "PasswordP123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                val refreshedP = servicesP.tokens.refresh(userP!!.id, tokensP.refresh)
                refreshedP.access shouldNotBe tokensP.access
                refreshedP.access.shouldNotBeNull()

                servicesP.tokens.verify(refreshedP.access).shouldNotBeNull()
                servicesQ.tokens.verify(refreshedP.access).shouldBeNull()
            }
        }
    }

    test("same email can exist in multiple realms with independent tokens") {
        testApplication {
            val realmM = Realm("realm-m")
            val realmN = Realm("realm-n")

            application {
                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:multi-realm-login;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(realmM) {
                        claims {
                            issuer("m-issuer")
                            audience("m-audience")
                        }
                        secrets {
                            raw("realm-m-secret-key-32-chars!!!!")
                        }
                    }
                    realm(realmN) {
                        claims {
                            issuer("n-issuer")
                            audience("n-audience")
                        }
                        secrets {
                            raw("realm-n-secret-key-32-chars!!!!")
                        }
                    }
                }

                val servicesM = kodex.servicesOf(realmM)
                val servicesN = kodex.servicesOf(realmN)

                // Create user in realm M
                val userM = servicesM.users.createUser(
                    email = "multiuser@example.com",
                    phone = null,
                    password = "SharedPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                userM.shouldNotBeNull()

                // Create user with same email in realm N (realm-scoped users allow this)
                val userN = servicesN.users.createUser(
                    email = "multiuser@example.com",
                    phone = null,
                    password = "SharedPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )
                userN.shouldNotBeNull()

                // Users should have different IDs (they are different users in different realms)
                userM!!.id shouldNotBe userN!!.id

                val tokensM = servicesM.auth.login(
                    "multiuser@example.com",
                    "SharedPassword123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                val tokensN = servicesN.auth.login(
                    "multiuser@example.com",
                    "SharedPassword123!",
                    "127.0.0.1",
                    "TestAgent"
                )

                tokensM.access shouldNotBe tokensN.access

                servicesM.tokens.verify(tokensM.access).shouldNotBeNull()
                servicesM.tokens.verify(tokensN.access).shouldBeNull()
                servicesN.tokens.verify(tokensN.access).shouldNotBeNull()
                servicesN.tokens.verify(tokensM.access).shouldBeNull()
            }
        }
    }
})
