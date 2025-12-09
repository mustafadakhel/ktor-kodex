package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.service.passwordHashingService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class EnrollmentSuccessResponse(
    val type: String,
    val backupCodes: List<String>
)

class EmailMfaEnrollmentTest : StringSpec({

    "Email MFA Enrollment - complete enrollment flow" {
        // Create emailSender in same scope as SimpleMfaTest does
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:email-mfa-test;DB_CLOSE_DELAY=-1;"
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
                                sender = emailSender
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

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/enroll/email") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfaService = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val email = params["email"] ?: return@post call.respondText("Missing email", status = HttpStatusCode.BadRequest)
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfaService.enrollEmail(userId, email, ipAddress)) {
                                    is EnrollmentResult.CodeSent -> call.respond(
                                        mapOf("type" to "CodeSent", "challengeId" to result.challengeId.toString())
                                    )
                                    is EnrollmentResult.RateLimitExceeded -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        mapOf("type" to "RateLimitExceeded", "reason" to result.reason)
                                    )
                                    is EnrollmentResult.Cooldown -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        mapOf("type" to "Cooldown", "reason" to result.reason)
                                    )
                                    is EnrollmentResult.Failed -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("type" to "Failed", "reason" to result.reason)
                                    )
                                }
                            }

                            post("/enroll/email/verify") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfaService = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val challengeId = params["challengeId"]?.let { UUID.fromString(it) }
                                    ?: return@post call.respondText("Missing challengeId", status = HttpStatusCode.BadRequest)
                                val code = params["code"] ?: return@post call.respondText("Missing code", status = HttpStatusCode.BadRequest)

                                when (val result = mfaService.verifyEmailEnrollment(userId, challengeId, code)) {
                                    is EnrollmentVerificationResult.Success -> call.respond(
                                        EnrollmentSuccessResponse("Success", result.backupCodes)
                                    )
                                    is EnrollmentVerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("type" to "Invalid", "reason" to result.reason)
                                    )
                                    is EnrollmentVerificationResult.Expired -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("type" to "Expired", "reason" to result.reason)
                                    )
                                    is EnrollmentVerificationResult.RateLimitExceeded -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        mapOf("type" to "RateLimitExceeded", "reason" to result.reason)
                                    )
                                }
                            }

                            get("/methods") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfaService = call.extensionService<MfaService>(realm)
                                    ?: return@get call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val methods = mfaService.getMethods(userId)
                                call.respond(methods.map { mapOf("type" to it.type.name, "identifier" to it.identifier) })
                            }
                        }
                    }
                }
            }

            // Force application to start before using captured variables
            startApplication()

            // Step 1: Initiate email enrollment
            val enrollResponse = client.post("/mfa/enroll/email") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("email", "user@example.com")
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            enrollResponse.status shouldBe HttpStatusCode.OK
            val enrollJson = Json.parseToJsonElement(enrollResponse.bodyAsText()).jsonObject
            enrollJson["type"]?.jsonPrimitive?.content shouldBe "CodeSent"
            val challengeId = enrollJson["challengeId"]?.jsonPrimitive?.content
            challengeId.shouldNotBeNull()

            // Step 2: Verify the code was sent
            val sentCode = emailSender.getLastCode("user@example.com")
            sentCode.shouldNotBeNull()

            // Step 3: Verify enrollment with the sent code
            val verifyResponse = client.post("/mfa/enroll/email/verify") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("challengeId", challengeId)
                    append("code", sentCode)
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            verifyResponse.status shouldBe HttpStatusCode.OK
            val verifyJson = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonObject
            verifyJson["type"]?.jsonPrimitive?.content shouldBe "Success"
            val backupCodes = verifyJson["backupCodes"]?.jsonArray
            backupCodes.shouldNotBeNull()
            backupCodes.shouldNotBeEmpty()

            // Step 4: Verify method was added
            val methodsResponse = client.get("/mfa/methods") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }

            methodsResponse.status shouldBe HttpStatusCode.OK
            val methods = methodsResponse.bodyAsText()
            methods shouldContain "user@example.com"
        }
    }
})
