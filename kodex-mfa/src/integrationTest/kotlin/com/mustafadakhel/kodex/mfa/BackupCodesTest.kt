package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.service.passwordHashingService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

@Serializable
data class BackupCodesResponse(
    val type: String,
    val backupCodes: List<String>? = null,
    val reason: String? = null
)

class BackupCodesTest : StringSpec({

    "Backup codes are generated during MFA enrollment" {
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
                        jdbcUrl = "jdbc:h2:mem:backup-codes-enroll-test;DB_CLOSE_DELAY=-1;"
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
                            backupCodes {
                                codeCount = 10
                                codeLength = 8
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
                                val mfaService = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val email = params["email"]!!
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfaService.enrollEmail(userId, email, ipAddress)) {
                                    is EnrollmentResult.CodeSent -> call.respond(
                                        mapOf("type" to "CodeSent", "challengeId" to result.challengeId.toString())
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Enrollment failed")
                                }
                            }

                            post("/enroll/email/verify") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfaService = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val challengeId = java.util.UUID.fromString(params["challengeId"]!!)
                                val code = params["code"]!!

                                when (val result = mfaService.verifyEmailEnrollment(userId, challengeId, code)) {
                                    is EnrollmentVerificationResult.Success -> call.respond(
                                        EnrollmentSuccessResponse("Success", result.backupCodes)
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Verification failed")
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            // Enroll email MFA
            val enrollResponse = client.post("/mfa/enroll/email") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("email", "user@example.com") }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            enrollResponse.status shouldBe HttpStatusCode.OK
            val challengeId = Json.parseToJsonElement(enrollResponse.bodyAsText()).jsonObject["challengeId"]?.jsonPrimitive?.content!!

            val code = emailSender.getLastCode("user@example.com")!!

            // Verify enrollment and get backup codes
            val verifyResponse = client.post("/mfa/enroll/email/verify") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("challengeId", challengeId)
                    append("code", code)
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            verifyResponse.status shouldBe HttpStatusCode.OK
            val responseJson = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonObject
            responseJson["type"]?.jsonPrimitive?.content shouldBe "Success"

            val backupCodes = responseJson["backupCodes"]?.jsonArray
            backupCodes.shouldNotBeNull()
            backupCodes shouldHaveSize 10  // Configured codeCount

            // Each backup code should be 8 characters (configured codeLength)
            backupCodes.forEach { code ->
                code.jsonPrimitive.content.length shouldBe 8
            }
        }
    }

    "Backup code can be used for MFA verification" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var backupCodes: List<String>? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:backup-codes-verify-test;DB_CLOSE_DELAY=-1;"
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
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create user and enroll MFA
                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val enrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val challengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
                val code = emailSender.getLastCode("user@example.com")!!
                val verifyResult = mfaService.verifyEmailEnrollment(user.id, challengeId, code)
                backupCodes = (verifyResult as EnrollmentVerificationResult.Success).backupCodes

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/verify/backup") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val backupCode = params["code"]!!
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.verifyBackupCode(userId, backupCode, ipAddress)) {
                                    is VerificationResult.Success -> call.respond(
                                        mapOf("type" to "Success")
                                    )
                                    is VerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("type" to "Invalid", "reason" to result.reason)
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Verification failed")
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            // Use the first backup code
            val firstBackupCode = backupCodes!!.first()
            val verifyResponse = client.post("/mfa/verify/backup") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("code", firstBackupCode) }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            verifyResponse.status shouldBe HttpStatusCode.OK
            val responseJson = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonObject
            responseJson["type"]?.jsonPrimitive?.content shouldBe "Success"
        }
    }

    "Same backup code cannot be used twice" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var backupCodes: List<String>? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:backup-codes-reuse-test;DB_CLOSE_DELAY=-1;"
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
                val mfaService = services.getExtensionService<MfaService>()!!

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val enrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val challengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
                val code = emailSender.getLastCode("user@example.com")!!
                val verifyResult = mfaService.verifyEmailEnrollment(user.id, challengeId, code)
                backupCodes = (verifyResult as EnrollmentVerificationResult.Success).backupCodes

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/verify/backup") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val backupCode = params["code"]!!
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.verifyBackupCode(userId, backupCode, ipAddress)) {
                                    is VerificationResult.Success -> call.respond(
                                        mapOf("type" to "Success")
                                    )
                                    is VerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("type" to "Invalid", "reason" to result.reason)
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Verification failed")
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            val firstBackupCode = backupCodes!!.first()

            // First use should succeed
            val firstResponse = client.post("/mfa/verify/backup") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("code", firstBackupCode) }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }
            firstResponse.status shouldBe HttpStatusCode.OK

            // Second use of same code should fail
            val secondResponse = client.post("/mfa/verify/backup") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("code", firstBackupCode) }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }
            secondResponse.status shouldBe HttpStatusCode.BadRequest
            val responseJson = Json.parseToJsonElement(secondResponse.bodyAsText()).jsonObject
            responseJson["type"]?.jsonPrimitive?.content shouldBe "Invalid"
        }
    }

    "Regenerate backup codes" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var oldBackupCodes: List<String>? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:backup-codes-regen-test;DB_CLOSE_DELAY=-1;"
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
                val mfaService = services.getExtensionService<MfaService>()!!

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                val enrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val challengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
                val code = emailSender.getLastCode("user@example.com")!!
                val verifyResult = mfaService.verifyEmailEnrollment(user.id, challengeId, code)
                oldBackupCodes = (verifyResult as EnrollmentVerificationResult.Success).backupCodes

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/backup-codes/regenerate") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val newCodes = mfa.generateBackupCodes(userId)
                                call.respond(BackupCodesResponse("Success", newCodes))
                            }

                            post("/verify/backup") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val backupCode = params["code"]!!
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.verifyBackupCode(userId, backupCode, ipAddress)) {
                                    is VerificationResult.Success -> call.respond(mapOf("type" to "Success"))
                                    is VerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("type" to "Invalid", "reason" to result.reason)
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Verification failed")
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            // Regenerate backup codes
            val regenResponse = client.post("/mfa/backup-codes/regenerate") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }

            regenResponse.status shouldBe HttpStatusCode.OK
            val regenJson = Json.parseToJsonElement(regenResponse.bodyAsText()).jsonObject
            val newBackupCodes = regenJson["backupCodes"]?.jsonArray?.map { it.jsonPrimitive.content }
            newBackupCodes.shouldNotBeNull()
            newBackupCodes shouldHaveSize 10

            // New codes should be different from old codes
            newBackupCodes.none { it in oldBackupCodes!! } shouldBe true

            // Old codes should no longer work
            val oldCodeResponse = client.post("/mfa/verify/backup") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("code", oldBackupCodes!!.first()) }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }
            oldCodeResponse.status shouldBe HttpStatusCode.BadRequest

            // New codes should work
            val newCodeResponse = client.post("/mfa/verify/backup") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("code", newBackupCodes.first()) }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }
            newCodeResponse.status shouldBe HttpStatusCode.OK
        }
    }
})
