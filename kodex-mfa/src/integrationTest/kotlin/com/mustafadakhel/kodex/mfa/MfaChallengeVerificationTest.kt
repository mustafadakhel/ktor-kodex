package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.service.passwordHashingService
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import io.kotest.core.spec.style.StringSpec
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.codec.binary.Base32
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class ChallengeResponse(
    val type: String,
    val challengeId: String? = null,
    val reason: String? = null
)

@Serializable
data class VerificationResponse(
    val type: String,
    val reason: String? = null
)

class MfaChallengeVerificationTest : StringSpec({

    "Email MFA Challenge - verify code after challenge" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var userId: UUID? = null
            var methodId: UUID? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-challenge-test;DB_CLOSE_DELAY=-1;"
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

                // Create user and enroll email MFA
                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!
                userId = user.id

                // Enroll email MFA
                val enrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val challengeIdForEnroll = (enrollResult as EnrollmentResult.CodeSent).challengeId
                val enrollCode = emailSender.getLastCode("user@example.com")!!
                val verifyResult = mfaService.verifyEmailEnrollment(user.id, challengeIdForEnroll, enrollCode)
                (verifyResult as EnrollmentVerificationResult.Success)

                // Get the enrolled method ID
                val methods = mfaService.getMethods(user.id)
                methodId = methods.first().id

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/challenge/email") {
                                val uid = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val mid = params["methodId"]?.let { UUID.fromString(it) }
                                    ?: return@post call.respondText("Missing methodId", status = HttpStatusCode.BadRequest)
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.challengeEmail(uid, mid, ipAddress)) {
                                    is ChallengeResult.Success -> call.respond(
                                        ChallengeResponse("Success", result.challengeId.toString())
                                    )
                                    is ChallengeResult.RateLimitExceeded -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        ChallengeResponse("RateLimitExceeded", reason = result.reason)
                                    )
                                    is ChallengeResult.Cooldown -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        ChallengeResponse("Cooldown", reason = result.reason)
                                    )
                                    is ChallengeResult.Failed -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        ChallengeResponse("Failed", reason = result.reason)
                                    )
                                }
                            }

                            post("/verify") {
                                val uid = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val cid = params["challengeId"]?.let { UUID.fromString(it) }
                                    ?: return@post call.respondText("Missing challengeId", status = HttpStatusCode.BadRequest)
                                val code = params["code"] ?: return@post call.respondText("Missing code", status = HttpStatusCode.BadRequest)
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.verifyChallenge(uid, cid, code, ipAddress)) {
                                    is VerificationResult.Success -> call.respond(
                                        VerificationResponse("Success")
                                    )
                                    is VerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        VerificationResponse("Invalid", result.reason)
                                    )
                                    is VerificationResult.Expired -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        VerificationResponse("Expired", result.reason)
                                    )
                                    is VerificationResult.RateLimitExceeded -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        VerificationResponse("RateLimitExceeded", result.reason)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            // Step 1: Request a challenge
            val challengeResponse = client.post("/mfa/challenge/email") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("methodId", methodId.toString())
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            challengeResponse.status shouldBe HttpStatusCode.OK
            val challengeJson = Json.parseToJsonElement(challengeResponse.bodyAsText()).jsonObject
            challengeJson["type"]?.jsonPrimitive?.content shouldBe "Success"
            val challengeId = challengeJson["challengeId"]?.jsonPrimitive?.content
            challengeId.shouldNotBeNull()

            // Step 2: Get the code from the email sender
            val code = emailSender.getLastCode("user@example.com")
            code.shouldNotBeNull()

            // Step 3: Verify the challenge
            val verifyResponse = client.post("/mfa/verify") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("challengeId", challengeId)
                    append("code", code)
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            verifyResponse.status shouldBe HttpStatusCode.OK
            val verifyJson = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonObject
            verifyJson["type"]?.jsonPrimitive?.content shouldBe "Success"
        }
    }

    "TOTP MFA Verification - verify TOTP code" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var userId: UUID? = null
            var methodId: UUID? = null
            var totpSecret: String? = null

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-totp-verify-test;DB_CLOSE_DELAY=-1;"
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
                            totpMfa {
                                issuer = "TestApp"
                            }
                            encryption {
                                aesGcm("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                            }
                        }
                    }
                }

                val services = kodex.servicesOf(realm)
                val mfaService = services.getExtensionService<MfaService>()!!

                // Create user and enroll TOTP MFA
                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!
                userId = user.id

                // Enroll TOTP MFA
                val enrollResult = mfaService.enrollTotp(user.id, "user@example.com")
                totpSecret = enrollResult.secret
                methodId = enrollResult.methodId

                // Verify TOTP enrollment
                val totpCode = generateTotpCode(totpSecret!!)
                val verifyResult = mfaService.verifyTotpEnrollment(user.id, enrollResult.methodId, totpCode)
                (verifyResult as EnrollmentVerificationResult.Success)

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/verify/totp") {
                                val uid = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val mid = params["methodId"]?.let { UUID.fromString(it) }
                                    ?: return@post call.respondText("Missing methodId", status = HttpStatusCode.BadRequest)
                                val code = params["code"] ?: return@post call.respondText("Missing code", status = HttpStatusCode.BadRequest)
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.verifyTotp(uid, mid, code, ipAddress)) {
                                    is VerificationResult.Success -> call.respond(
                                        VerificationResponse("Success")
                                    )
                                    is VerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        VerificationResponse("Invalid", result.reason)
                                    )
                                    is VerificationResult.Expired -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        VerificationResponse("Expired", result.reason)
                                    )
                                    is VerificationResult.RateLimitExceeded -> call.respond(
                                        HttpStatusCode.TooManyRequests,
                                        VerificationResponse("RateLimitExceeded", result.reason)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            // Generate a fresh TOTP code and verify
            val code = generateTotpCode(totpSecret!!)

            val verifyResponse = client.post("/mfa/verify/totp") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("methodId", methodId.toString())
                    append("code", code)
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            verifyResponse.status shouldBe HttpStatusCode.OK
            val verifyJson = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonObject
            verifyJson["type"]?.jsonPrimitive?.content shouldBe "Success"
        }
    }

    "Invalid code should fail verification" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var methodId: UUID? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-invalid-code-test;DB_CLOSE_DELAY=-1;"
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

                // Enroll email MFA
                val enrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val challengeIdForEnroll = (enrollResult as EnrollmentResult.CodeSent).challengeId
                val enrollCode = emailSender.getLastCode("user@example.com")!!
                mfaService.verifyEmailEnrollment(user.id, challengeIdForEnroll, enrollCode)

                val methods = mfaService.getMethods(user.id)
                methodId = methods.first().id

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa") {
                            post("/challenge/email") {
                                val uid = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val mid = UUID.fromString(params["methodId"]!!)
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.challengeEmail(uid, mid, ipAddress)) {
                                    is ChallengeResult.Success -> call.respond(
                                        ChallengeResponse("Success", result.challengeId.toString())
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Challenge failed")
                                }
                            }

                            post("/verify") {
                                val uid = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val cid = UUID.fromString(params["challengeId"]!!)
                                val code = params["code"]!!
                                val ipAddress = call.request.local.remoteHost

                                when (val result = mfa.verifyChallenge(uid, cid, code, ipAddress)) {
                                    is VerificationResult.Success -> call.respond(VerificationResponse("Success"))
                                    is VerificationResult.Invalid -> call.respond(
                                        HttpStatusCode.BadRequest,
                                        VerificationResponse("Invalid", result.reason)
                                    )
                                    else -> call.respond(HttpStatusCode.BadRequest, "Verification failed")
                                }
                            }
                        }
                    }
                }
            }

            startApplication()

            // Request a challenge
            val challengeResponse = client.post("/mfa/challenge/email") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("methodId", methodId.toString())
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            val challengeJson = Json.parseToJsonElement(challengeResponse.bodyAsText()).jsonObject
            val challengeId = challengeJson["challengeId"]?.jsonPrimitive?.content!!

            // Verify with an INVALID code
            val verifyResponse = client.post("/mfa/verify") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("challengeId", challengeId)
                    append("code", "000000") // Wrong code
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            verifyResponse.status shouldBe HttpStatusCode.BadRequest
            val verifyJson = Json.parseToJsonElement(verifyResponse.bodyAsText()).jsonObject
            verifyJson["type"]?.jsonPrimitive?.content shouldBe "Invalid"
        }
    }
})

private fun generateTotpCode(base32Secret: String): String {
    val secretBytes = Base32().decode(base32Secret)
    val config = TimeBasedOneTimePasswordConfig(
        codeDigits = 6,
        hmacAlgorithm = HmacAlgorithm.SHA1,
        timeStep = 30,
        timeStepUnit = TimeUnit.SECONDS
    )
    val generator = TimeBasedOneTimePasswordGenerator(secretBytes, config)
    return generator.generate()
}
