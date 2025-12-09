package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.service.passwordHashingService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.util.UUID

@Serializable
data class TrustDeviceResponse(
    val type: String,
    val deviceId: String? = null
)

@Serializable
data class TrustedDeviceDto(
    val id: String,
    val deviceName: String?,
    val ipAddress: String?,
    val userAgent: String?
)

class TrustedDevicesTest : StringSpec({

    "Trust a device and check if trusted" {
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
                        jdbcUrl = "jdbc:h2:mem:trusted-device-test;DB_CLOSE_DELAY=-1;"
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
                            autoTrustDeviceAfterVerification = true
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
                mfaService.verifyEmailEnrollment(user.id, challengeId, code)

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa/devices") {
                            post("/trust") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val params = call.receiveParameters()
                                val deviceName = params["deviceName"]
                                val ipAddress = call.request.local.remoteHost
                                val userAgent = call.request.userAgent()

                                val deviceId = mfa.trustDevice(userId, ipAddress, userAgent, deviceName)
                                call.respond(TrustDeviceResponse("Success", deviceId.toString()))
                            }

                            get("/check") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val ipAddress = call.request.local.remoteHost
                                val userAgent = call.request.userAgent()

                                val isTrusted = mfa.isDeviceTrusted(userId, ipAddress, userAgent)
                                call.respond(mapOf("trusted" to isTrusted))
                            }

                            get("/list") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!

                                val devices = mfa.getTrustedDevices(userId)
                                call.respond(devices.map {
                                    TrustedDeviceDto(
                                        id = it.id.toString(),
                                        deviceName = it.deviceName,
                                        ipAddress = it.ipAddress,
                                        userAgent = it.userAgent
                                    )
                                })
                            }
                        }
                    }
                }
            }

            startApplication()

            // Check that device is NOT trusted initially
            val checkBefore = client.get("/mfa/devices/check") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            checkBefore.status shouldBe HttpStatusCode.OK
            val beforeJson = Json.parseToJsonElement(checkBefore.bodyAsText()).jsonObject
            beforeJson["trusted"]?.jsonPrimitive?.content shouldBe "false"

            // Trust the device
            val trustResponse = client.post("/mfa/devices/trust") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                setBody(Parameters.build { append("deviceName", "My Laptop") }.formUrlEncode())
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
            }

            trustResponse.status shouldBe HttpStatusCode.OK
            val trustJson = Json.parseToJsonElement(trustResponse.bodyAsText()).jsonObject
            trustJson["type"]?.jsonPrimitive?.content shouldBe "Success"
            val deviceId = trustJson["deviceId"]?.jsonPrimitive?.content
            deviceId.shouldNotBeNull()

            // Check that device is now trusted
            val checkAfter = client.get("/mfa/devices/check") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            checkAfter.status shouldBe HttpStatusCode.OK
            val afterJson = Json.parseToJsonElement(checkAfter.bodyAsText()).jsonObject
            afterJson["trusted"]?.jsonPrimitive?.content shouldBe "true"

            // List trusted devices
            val listResponse = client.get("/mfa/devices/list") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            listResponse.status shouldBe HttpStatusCode.OK
            val devices = Json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
            devices shouldHaveSize 1
            devices[0].jsonObject["deviceName"]?.jsonPrimitive?.content shouldBe "My Laptop"
        }
    }

    "Remove a trusted device" {
        testApplication {
            val realm = Realm("test-realm")
            var accessToken: String? = null
            var deviceId: UUID? = null
            val emailSender = MockEmailSender()

            application {
                install(ContentNegotiation) {
                    json()
                }

                val kodex = install(Kodex) {
                    database {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl = "jdbc:h2:mem:remove-device-test;DB_CLOSE_DELAY=-1;"
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
                            autoTrustDeviceAfterVerification = true
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
                mfaService.verifyEmailEnrollment(user.id, challengeId, code)

                // Trust a device
                deviceId = mfaService.trustDevice(user.id, "192.168.1.1", "TestBrowser/1.0", "My Device")

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa/devices") {
                            get("/list") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val devices = mfa.getTrustedDevices(userId)
                                call.respond(devices.map {
                                    TrustedDeviceDto(it.id.toString(), it.deviceName, it.ipAddress, it.userAgent)
                                })
                            }

                            delete("/{deviceId}") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val did = UUID.fromString(call.parameters["deviceId"]!!)
                                mfa.removeTrustedDevice(userId, did)
                                call.respond(mapOf("type" to "Success"))
                            }
                        }
                    }
                }
            }

            startApplication()

            // Verify device is in list
            val listBefore = client.get("/mfa/devices/list") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            Json.parseToJsonElement(listBefore.bodyAsText()).jsonArray shouldHaveSize 1

            // Remove device
            val removeResponse = client.delete("/mfa/devices/${deviceId}") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            removeResponse.status shouldBe HttpStatusCode.OK

            // Verify device is no longer in list
            val listAfter = client.get("/mfa/devices/list") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            Json.parseToJsonElement(listAfter.bodyAsText()).jsonArray.shouldBeEmpty()
        }
    }

    "Remove all trusted devices" {
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
                        jdbcUrl = "jdbc:h2:mem:remove-all-devices-test;DB_CLOSE_DELAY=-1;"
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
                            autoTrustDeviceAfterVerification = true
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
                mfaService.verifyEmailEnrollment(user.id, challengeId, code)

                // Trust multiple devices
                mfaService.trustDevice(user.id, "192.168.1.1", "Chrome/1.0", "Device 1")
                mfaService.trustDevice(user.id, "192.168.1.2", "Firefox/1.0", "Device 2")
                mfaService.trustDevice(user.id, "192.168.1.3", "Safari/1.0", "Device 3")

                accessToken = services.auth.login(
                    "user@example.com",
                    "TestPassword123!",
                    "192.168.1.1",
                    "TestBrowser/1.0"
                ).access

                routing {
                    authenticateFor(realm) {
                        route("/mfa/devices") {
                            get("/list") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                val devices = mfa.getTrustedDevices(userId)
                                call.respond(devices.map {
                                    TrustedDeviceDto(it.id.toString(), it.deviceName, it.ipAddress, it.userAgent)
                                })
                            }

                            delete("/all") {
                                val userId = with(KodexId) { call.idOrFail() }
                                val mfa = call.extensionService<MfaService>(realm)!!
                                mfa.removeAllTrustedDevices(userId)
                                call.respond(mapOf("type" to "Success"))
                            }
                        }
                    }
                }
            }

            startApplication()

            // Verify multiple devices are in list
            val listBefore = client.get("/mfa/devices/list") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            Json.parseToJsonElement(listBefore.bodyAsText()).jsonArray shouldHaveSize 3

            // Remove all devices
            val removeResponse = client.delete("/mfa/devices/all") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            removeResponse.status shouldBe HttpStatusCode.OK

            // Verify no devices remain
            val listAfter = client.get("/mfa/devices/list") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            Json.parseToJsonElement(listAfter.bodyAsText()).jsonArray.shouldBeEmpty()
        }
    }
})
