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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
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
import org.apache.commons.codec.binary.Base32
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class TotpEnrollResponse(
    val type: String,
    val methodId: String,
    val secret: String,
    val qrCodeDataUri: String,
    val issuer: String,
    val accountName: String
)

class TotpMfaEnrollmentTest : StringSpec({

    "TOTP MFA Enrollment - complete enrollment flow" {
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
                        jdbcUrl = "jdbc:h2:mem:totp-mfa-test;DB_CLOSE_DELAY=-1;"
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
                            post("/enroll/totp") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfaService = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val accountName = params["accountName"] ?: "user@example.com"

                                val result = mfaService.enrollTotp(userId, accountName)
                                call.respond(
                                    TotpEnrollResponse(
                                        type = "EnrollmentStarted",
                                        methodId = result.methodId.toString(),
                                        secret = result.secret,
                                        qrCodeDataUri = result.qrCodeDataUri,
                                        issuer = result.issuer,
                                        accountName = result.accountName
                                    )
                                )
                            }

                            post("/enroll/totp/verify") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfaService = call.extensionService<MfaService>(realm)
                                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                                val params = call.receiveParameters()
                                val methodId = params["methodId"]?.let { UUID.fromString(it) }
                                    ?: return@post call.respondText("Missing methodId", status = HttpStatusCode.BadRequest)
                                val code = params["code"] ?: return@post call.respondText("Missing code", status = HttpStatusCode.BadRequest)

                                when (val result = mfaService.verifyTotpEnrollment(userId, methodId, code)) {
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

            // Step 1: Initiate TOTP enrollment
            val enrollResponse = client.post("/mfa/enroll/totp") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("accountName", "user@example.com")
                }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            enrollResponse.status shouldBe HttpStatusCode.OK
            val enrollJson = Json.parseToJsonElement(enrollResponse.bodyAsText()).jsonObject
            enrollJson["type"]?.jsonPrimitive?.content shouldBe "EnrollmentStarted"
            val methodId = enrollJson["methodId"]?.jsonPrimitive?.content
            methodId.shouldNotBeNull()
            val secret = enrollJson["secret"]?.jsonPrimitive?.content
            secret.shouldNotBeNull()
            val qrCodeDataUri = enrollJson["qrCodeDataUri"]?.jsonPrimitive?.content
            qrCodeDataUri.shouldNotBeNull()
            qrCodeDataUri shouldStartWith "data:image/png;base64,"
            enrollJson["issuer"]?.jsonPrimitive?.content shouldBe "TestApp"
            enrollJson["accountName"]?.jsonPrimitive?.content shouldBe "user@example.com"

            // Step 2: Generate TOTP code from secret
            val totpCode = generateTotpCode(secret)
            totpCode.shouldNotBeNull()

            // Step 3: Verify enrollment with the generated code
            val verifyResponse = client.post("/mfa/enroll/totp/verify") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build {
                    append("methodId", methodId)
                    append("code", totpCode)
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
            methods shouldContain "TOTP"
        }
    }
})

/**
 * Generate a TOTP code from a Base32-encoded secret
 */
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
