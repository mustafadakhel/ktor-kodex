package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.kodex
import com.mustafadakhel.kodex.service.KodexRealmService
import io.kotest.core.spec.style.StringSpec
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
                val adminService = kodex.serviceOf(adminRealm) as KodexRealmService
                val userService = kodex.serviceOf(userRealm) as KodexRealmService
                adminService.realm shouldBe adminRealm
                userService.realm shouldBe userRealm
            }
        }
    }
})
