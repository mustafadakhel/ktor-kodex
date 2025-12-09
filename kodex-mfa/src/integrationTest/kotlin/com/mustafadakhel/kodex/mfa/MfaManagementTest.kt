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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
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
data class MfaMethodDto(
    val id: String,
    val type: String,
    val identifier: String?,
    val isPrimary: Boolean
)

class MfaManagementTest : StringSpec({

    "List enrolled MFA methods" {
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
                        jdbcUrl = "jdbc:h2:mem:mfa-list-methods-test;DB_CLOSE_DELAY=-1;"
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

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA
                val emailEnrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("user@example.com")!!
                mfaService.verifyEmailEnrollment(user.id, emailChallengeId, emailCode)

                // Enroll TOTP MFA
                val totpEnrollResult = mfaService.enrollTotp(user.id, "user@example.com")
                val totpCode = generateTotpCode(totpEnrollResult.secret)
                mfaService.verifyTotpEnrollment(user.id, totpEnrollResult.methodId, totpCode)

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        get("/mfa/methods") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val methods = mfa.getMethods(userId)
                            call.respond(methods.map {
                                MfaMethodDto(it.id.toString(), it.type.name, it.identifier, it.isPrimary)
                            })
                        }
                    }
                }
            }

            startApplication()

            val response = client.get("/mfa/methods") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }

            response.status shouldBe HttpStatusCode.OK
            val methods = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            methods shouldHaveSize 2

            val types = methods.map { it.jsonObject["type"]?.jsonPrimitive?.content }
            types.contains("EMAIL") shouldBe true
            types.contains("TOTP") shouldBe true
        }
    }

    "Remove MFA method" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var emailMethodId: UUID? = null
            var totpMethodId: UUID? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-remove-method-test;DB_CLOSE_DELAY=-1;"
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

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA
                val emailEnrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("user@example.com")!!
                mfaService.verifyEmailEnrollment(user.id, emailChallengeId, emailCode)

                // Enroll TOTP MFA
                val totpEnrollResult = mfaService.enrollTotp(user.id, "user@example.com")
                val totpCode = generateTotpCode(totpEnrollResult.secret)
                mfaService.verifyTotpEnrollment(user.id, totpEnrollResult.methodId, totpCode)

                // Get method IDs
                val methods = mfaService.getMethods(user.id)
                emailMethodId = methods.find { it.type.name == "EMAIL" }?.id
                totpMethodId = methods.find { it.type.name == "TOTP" }?.id

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        get("/mfa/methods") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val methods = mfa.getMethods(userId)
                            call.respond(methods.map {
                                MfaMethodDto(it.id.toString(), it.type.name, it.identifier, it.isPrimary)
                            })
                        }

                        delete("/mfa/methods/{methodId}") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val methodId = UUID.fromString(call.parameters["methodId"]!!)
                            mfa.removeMethod(userId, methodId)
                            call.respond(mapOf("type" to "Success"))
                        }
                    }
                }
            }

            startApplication()

            // Verify both methods exist
            val listBefore = client.get("/mfa/methods") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            Json.parseToJsonElement(listBefore.bodyAsText()).jsonArray shouldHaveSize 2

            // Remove TOTP method
            val removeResponse = client.delete("/mfa/methods/${totpMethodId}") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            removeResponse.status shouldBe HttpStatusCode.OK

            // Verify only email method remains
            val listAfter = client.get("/mfa/methods") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val methodsAfter = Json.parseToJsonElement(listAfter.bodyAsText()).jsonArray
            methodsAfter shouldHaveSize 1
            methodsAfter[0].jsonObject["type"]?.jsonPrimitive?.content shouldBe "EMAIL"
        }
    }

    "Set primary MFA method" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var totpMethodId: UUID? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:mfa-set-primary-test;DB_CLOSE_DELAY=-1;"
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

                val user = services.users.createUser(
                    email = "user@example.com",
                    phone = null,
                    password = "TestPassword123!",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!

                // Enroll email MFA (will be primary initially)
                val emailEnrollResult = mfaService.enrollEmail(user.id, "user@example.com", "192.168.1.1")
                val emailChallengeId = (emailEnrollResult as EnrollmentResult.CodeSent).challengeId
                val emailCode = emailSender.getLastCode("user@example.com")!!
                mfaService.verifyEmailEnrollment(user.id, emailChallengeId, emailCode)

                // Enroll TOTP MFA
                val totpEnrollResult = mfaService.enrollTotp(user.id, "user@example.com")
                val totpCode = generateTotpCode(totpEnrollResult.secret)
                mfaService.verifyTotpEnrollment(user.id, totpEnrollResult.methodId, totpCode)
                totpMethodId = totpEnrollResult.methodId

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        get("/mfa/methods") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val methods = mfa.getMethods(userId)
                            call.respond(methods.map {
                                MfaMethodDto(it.id.toString(), it.type.name, it.identifier, it.isPrimary)
                            })
                        }

                        post("/mfa/methods/{methodId}/primary") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val methodId = UUID.fromString(call.parameters["methodId"]!!)
                            mfa.setPrimaryMethod(userId, methodId)
                            call.respond(mapOf("type" to "Success"))
                        }
                    }
                }
            }

            startApplication()

            // Get methods and check which is primary
            val listBefore = client.get("/mfa/methods") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val methodsBefore = Json.parseToJsonElement(listBefore.bodyAsText()).jsonArray
            val emailIsPrimaryBefore = methodsBefore.find {
                it.jsonObject["type"]?.jsonPrimitive?.content == "EMAIL"
            }?.jsonObject?.get("isPrimary")?.jsonPrimitive?.content
            emailIsPrimaryBefore shouldBe "true"

            // Set TOTP as primary
            val setPrimaryResponse = client.post("/mfa/methods/${totpMethodId}/primary") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            setPrimaryResponse.status shouldBe HttpStatusCode.OK

            // Verify TOTP is now primary
            val listAfter = client.get("/mfa/methods") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val methodsAfter = Json.parseToJsonElement(listAfter.bodyAsText()).jsonArray
            val totpIsPrimaryAfter = methodsAfter.find {
                it.jsonObject["type"]?.jsonPrimitive?.content == "TOTP"
            }?.jsonObject?.get("isPrimary")?.jsonPrimitive?.content
            totpIsPrimaryAfter shouldBe "true"

            val emailIsPrimaryAfter = methodsAfter.find {
                it.jsonObject["type"]?.jsonPrimitive?.content == "EMAIL"
            }?.jsonObject?.get("isPrimary")?.jsonPrimitive?.content
            emailIsPrimaryAfter shouldBe "false"
        }
    }

    "Check if MFA is required" {
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
                        jdbcUrl = "jdbc:h2:mem:mfa-required-test;DB_CLOSE_DELAY=-1;"
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

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        get("/mfa/required") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val required = mfa.isMfaRequired(userId)
                            val hasAny = mfa.hasAnyMethod(userId)
                            call.respond(mapOf("required" to required, "hasAnyMethod" to hasAny))
                        }

                        post("/mfa/enroll/email") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val result = mfa.enrollEmail(userId, "user@example.com", "192.168.1.1")
                            when (result) {
                                is EnrollmentResult.CodeSent -> call.respond(
                                    mapOf("challengeId" to result.challengeId.toString())
                                )
                                else -> call.respond(HttpStatusCode.BadRequest, "Failed")
                            }
                        }

                        post("/mfa/enroll/email/verify") {
                            val userId = with(KodexId) { call.idOrFail() }
                            val mfa = call.extensionService<MfaService>(realm)!!
                            val challengeId = UUID.fromString(call.request.queryParameters["challengeId"]!!)
                            val code = call.request.queryParameters["code"]!!
                            val result = mfa.verifyEmailEnrollment(userId, challengeId, code)
                            when (result) {
                                is EnrollmentVerificationResult.Success -> call.respond(mapOf("type" to "Success"))
                                else -> call.respond(HttpStatusCode.BadRequest, "Failed")
                            }
                        }
                    }
                }
            }

            startApplication()

            // Before enrollment - no MFA methods
            val beforeEnroll = client.get("/mfa/required") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val beforeJson = Json.parseToJsonElement(beforeEnroll.bodyAsText()).jsonObject
            beforeJson["hasAnyMethod"]?.jsonPrimitive?.content shouldBe "false"

            // Enroll MFA
            val enrollResponse = client.post("/mfa/enroll/email") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val challengeId = Json.parseToJsonElement(enrollResponse.bodyAsText()).jsonObject["challengeId"]?.jsonPrimitive?.content!!
            val code = emailSender.getLastCode("user@example.com")!!

            client.post("/mfa/enroll/email/verify?challengeId=$challengeId&code=$code") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }

            // After enrollment - has MFA methods
            val afterEnroll = client.get("/mfa/required") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            val afterJson = Json.parseToJsonElement(afterEnroll.bodyAsText()).jsonObject
            afterJson["hasAnyMethod"]?.jsonPrimitive?.content shouldBe "true"
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
