package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.authorizedRoute
import com.mustafadakhel.kodex.service.passwordHashingService
import java.util.UUID
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*

class SimpleMfaTest : StringSpec({

    "simple MFA test - verify routing works" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:simple-mfa-test;DB_CLOSE_DELAY=-1;"
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
                        mfa {
                            hashingService = passwordHashingService()
                            emailMfa {
                                sender = MockEmailSender()
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)

                services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                setupTestRoutes(realm)
            }

            // Test public route
            val publicResponse = client.get("/public")
            publicResponse.status shouldBe HttpStatusCode.OK
            publicResponse.bodyAsText() shouldBe "Public works!"

            // Test protected route without token
            val unauthedResponse = client.get("/protected")
            unauthedResponse.status shouldBe HttpStatusCode.Unauthorized

            // Test protected route with token
            val authedResponse = client.get("/protected") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            authedResponse.status shouldBe HttpStatusCode.OK
            authedResponse.bodyAsText() shouldBe "Protected works!"

            // Test protected route that uses call.idOrFail()
            val idResponse = client.get("/protected-with-id") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            println("protected-with-id status: ${idResponse.status}")
            println("protected-with-id body: ${idResponse.bodyAsText()}")
            idResponse.status shouldBe HttpStatusCode.OK
            idResponse.bodyAsText() shouldContain "User ID:"

            // Test POST protected route that uses call.idOrFail()
            val postResponse = client.post("/protected-post") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            println("protected-post status: ${postResponse.status}")
            println("protected-post body: ${postResponse.bodyAsText()}")
            postResponse.status shouldBe HttpStatusCode.OK
            postResponse.bodyAsText() shouldContain "POST User ID:"

            // Test nested MFA route with call.idOrFail()
            val mfaResponse = client.post("/mfa/test-enroll") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            mfaResponse.status shouldBe HttpStatusCode.OK
            mfaResponse.bodyAsText() shouldContain "Enroll User ID:"
        }
    }
})

private fun Application.setupTestRoutes(realm: Realm) {
    routing {
        get("/public") {
            call.respondText("Public works!")
        }

        authenticateFor(realm) {
            get("/protected") {
                call.respondText("Protected works!")
            }

            get("/protected-with-id") {
                val userId = with(KodexId) { call.idOrFail() }
                call.respondText("User ID: $userId")
            }

            post("/protected-post") {
                val userId = with(KodexId) { call.idOrFail() }
                call.respondText("POST User ID: $userId")
            }

            route("/mfa") {
                post("/test-enroll") {
                    val userId = with(KodexId) { call.idOrFail() }
                    call.respondText("Enroll User ID: $userId")
                }
            }

            // TODO: Fix authorizedRoute - currently returns 404
            // authorizedRoute("/mfa", KodexId) {
            //     post("/hello") { userId: UUID ->
            //         call.respondText("Hello user $userId!")
            //     }
            // }
        }
    }
}
