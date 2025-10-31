package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.kodex
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

class KodexMultiRealmTest : StringSpec({
    "serviceOf and authenticateFor work for multiple realms" {
        val adminRealm = Realm("admin")
        val userRealm = Realm("user")

        testApplication {
            application {
                val actualKodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:test-db;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(adminRealm) {
                        claims {
                            issuer("issuer")
                            audience("audience")
                        }
                        secrets {
                            raw("secret1")
                        }
                    }
                    realm(userRealm) {
                        claims {
                            issuer("issuer")
                            audience("audience")
                        }
                        secrets {
                            raw("secret2")
                        }
                    }
                }
                routing {
                    authenticateFor(adminRealm, userRealm) {
                        get("/protected") {
                            call.kodex.shouldBe(actualKodex)
                            call.respondText("ok")
                        }
                    }
                }
                val kodex = plugin(Kodex)
                val adminServices = kodex.servicesOf(adminRealm)
                val userServices = kodex.servicesOf(userRealm)
                adminServices.realm shouldBe adminRealm
                userServices.realm shouldBe userRealm
            }
        }
    }
    "roles of all realms are seeded" {
        val adminRealm = Realm("admin")
        val userRealm = Realm("user")

        testApplication {
            application {
                val actualKodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:test-db;DB_CLOSE_DELAY=-1;"
                        username = "test"
                        password = "test"
                    }
                    realm(adminRealm) {
                        roles {
                            role("maintain")
                        }
                        claims {
                            issuer("issuer")
                            audience("audience")
                        }
                        secrets {
                            raw("secret1")
                        }
                    }
                    realm(userRealm) {
                        roles {
                            role("view")
                        }
                        claims {
                            issuer("issuer")
                            audience("audience")
                        }
                        secrets {
                            raw("secret2")
                        }
                    }
                }

                actualKodex.servicesOf(adminRealm).users.getSeededRoles() shouldContainExactlyInAnyOrder listOf(
                    "admin",
                    "user",
                    "maintain",
                    "view"
                )
            }
        }
    }
})
