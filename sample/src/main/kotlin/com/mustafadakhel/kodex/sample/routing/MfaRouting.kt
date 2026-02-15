package com.mustafadakhel.kodex.sample.routing

import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.mfa.MfaService
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.authorizedRoute
import com.mustafadakhel.kodex.sample.DefaultRealms
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.setupMfaRouting() = routing {
    DefaultRealms.forEach { realm ->
        // Public MFA verification endpoint (no authentication required)
        route("/${realm.name}/mfa") {
            post("/verify") {
                val mfaService = call.extensionService<MfaService>(realm)
                    ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                val params = call.receiveParameters()
                val sessionId = params["sessionId"] ?: return@post call.respondText(
                    "Missing sessionId",
                    status = HttpStatusCode.BadRequest
                )
                val code = params["code"] ?: return@post call.respondText(
                    "Missing code",
                    status = HttpStatusCode.BadRequest
                )
                val methodId = params["methodId"]?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: IllegalArgumentException) {
                        return@post call.respondText("Invalid methodId format", status = HttpStatusCode.BadRequest)
                    }
                }

                try {
                    val result = mfaService.verifyMfaSession(sessionId, code, methodId)
                    when (result) {
                        is com.mustafadakhel.kodex.mfa.VerificationResult.Success -> {
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "success" to true,
                                    "message" to "MFA verified successfully. Device has been trusted. Please login again."
                                )
                            )
                        }
                        is com.mustafadakhel.kodex.mfa.VerificationResult.Invalid -> {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                mapOf("success" to false, "error" to result.reason)
                            )
                        }
                        is com.mustafadakhel.kodex.mfa.VerificationResult.Expired -> {
                            call.respond(
                                HttpStatusCode.Unauthorized,
                                mapOf("success" to false, "error" to result.reason)
                            )
                        }
                        is com.mustafadakhel.kodex.mfa.VerificationResult.RateLimitExceeded -> {
                            call.respond(
                                HttpStatusCode.TooManyRequests,
                                mapOf("success" to false, "error" to result.reason)
                            )
                        }
                    }
                } catch (e: Exception) {
                    call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                }
            }
        }

        authenticateFor(realm) {
            authorizedRoute("/${realm.name}/mfa", KodexId) {
                post("/enroll/email") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText(
                            "MFA not configured",
                            status = HttpStatusCode.InternalServerError
                        )

                    val params = call.receiveParameters()
                    val email = params["email"] ?: return@post call.respondText(
                        "Missing email",
                        status = HttpStatusCode.BadRequest
                    )
                    val ipAddress = call.request.local.remoteHost

                    try {
                        val result = mfaService.enrollEmail(userId, email, ipAddress)
                        call.respond(result)
                    } catch (e: Exception) {
                        call.respondText(
                            "Enrollment failed: ${e.message}",
                            status = HttpStatusCode.InternalServerError
                        )
                    }
                }

                post("/enroll/email/verify") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val params = call.receiveParameters()
                    val challengeIdStr = params["challengeId"] ?: return@post call.respondText(
                        "Missing challengeId",
                        status = HttpStatusCode.BadRequest
                    )
                    val code = params["code"] ?: return@post call.respondText(
                        "Missing code",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val challengeId = UUID.fromString(challengeIdStr)
                        val result = mfaService.verifyEmailEnrollment(userId, challengeId, code)
                        call.respond(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid challengeId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/enroll/totp") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val params = call.receiveParameters()
                    val accountName = params["accountName"] ?: return@post call.respondText(
                        "Missing accountName",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val result = mfaService.enrollTotp(userId, accountName)
                        call.respond(result)
                    } catch (e: Exception) {
                        call.respondText("Enrollment failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/enroll/totp/verify") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val params = call.receiveParameters()
                    val methodIdStr = params["methodId"] ?: return@post call.respondText(
                        "Missing methodId",
                        status = HttpStatusCode.BadRequest
                    )
                    val code = params["code"] ?: return@post call.respondText(
                        "Missing code",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val methodId = UUID.fromString(methodIdStr)
                        val result = mfaService.verifyTotpEnrollment(userId, methodId, code)
                        call.respond(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid methodId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }

            route("/${realm.name}/mfa") {
                get("/methods") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@get call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    try {
                        val methods = mfaService.getMethods(userId)
                        call.respond(methods)
                    } catch (e: Exception) {
                        call.respondText("Failed to get methods: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                get("/trusted-devices") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@get call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    try {
                        val devices = mfaService.getTrustedDevices(userId)
                        call.respond(devices)
                    } catch (e: Exception) {
                        call.respondText("Failed to get trusted devices: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }

            authorizedRoute("/${realm.name}/mfa", KodexId) {
                post("/methods/{methodId}/primary") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val methodIdStr = call.parameters["methodId"] ?: return@post call.respondText(
                        "Missing methodId",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val methodId = UUID.fromString(methodIdStr)
                        mfaService.setPrimaryMethod(userId, methodId)
                        call.respondText("Primary method set", status = HttpStatusCode.OK)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid methodId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Failed to set primary method: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/challenge/email/{methodId}") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val methodIdStr = call.parameters["methodId"] ?: return@post call.respondText(
                        "Missing methodId",
                        status = HttpStatusCode.BadRequest
                    )
                    val ipAddress = call.request.local.remoteHost

                    try {
                        val methodId = UUID.fromString(methodIdStr)
                        val result = mfaService.challengeEmail(userId, methodId, ipAddress)
                        call.respond(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid methodId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Challenge failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/challenge/verify") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val params = call.receiveParameters()
                    val challengeIdStr = params["challengeId"] ?: return@post call.respondText(
                        "Missing challengeId",
                        status = HttpStatusCode.BadRequest
                    )
                    val code = params["code"] ?: return@post call.respondText(
                        "Missing code",
                        status = HttpStatusCode.BadRequest
                    )
                    val ipAddress = call.request.local.remoteHost

                    try {
                        val challengeId = UUID.fromString(challengeIdStr)
                        val result = mfaService.verifyChallenge(userId, challengeId, code, ipAddress)
                        call.respond(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid challengeId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/totp/verify/{methodId}") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val methodIdStr = call.parameters["methodId"] ?: return@post call.respondText(
                        "Missing methodId",
                        status = HttpStatusCode.BadRequest
                    )
                    val params = call.receiveParameters()
                    val code = params["code"] ?: return@post call.respondText(
                        "Missing code",
                        status = HttpStatusCode.BadRequest
                    )
                    val ipAddress = call.request.local.remoteHost

                    try {
                        val methodId = UUID.fromString(methodIdStr)
                        val result = mfaService.verifyTotp(userId, methodId, code, ipAddress)
                        call.respond(result)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid methodId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/backup-codes/generate") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    try {
                        val codes = mfaService.generateBackupCodes(userId)
                        call.respond(mapOf("codes" to codes))
                    } catch (e: Exception) {
                        call.respondText("Failed to generate backup codes: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/backup-codes/verify") { userId: UUID ->
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val params = call.receiveParameters()
                    val code = params["code"] ?: return@post call.respondText(
                        "Missing code",
                        status = HttpStatusCode.BadRequest
                    )
                    val ipAddress = call.request.local.remoteHost

                    try {
                        val result = mfaService.verifyBackupCode(userId, code, ipAddress)
                        call.respond(result)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }

            route("/${realm.name}/mfa") {
                delete("/methods/{methodId}") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@delete call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val methodIdStr = call.parameters["methodId"] ?: return@delete call.respondText(
                        "Missing methodId",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val methodId = UUID.fromString(methodIdStr)
                        mfaService.removeMethod(userId, methodId)
                        call.respondText("Method removed", status = HttpStatusCode.OK)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid methodId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Failed to remove method: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                delete("/trusted-devices/{deviceId}") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@delete call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val deviceIdStr = call.parameters["deviceId"] ?: return@delete call.respondText(
                        "Missing deviceId",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val deviceId = UUID.fromString(deviceIdStr)
                        mfaService.removeTrustedDevice(userId, deviceId)
                        call.respondText("Trusted device removed", status = HttpStatusCode.OK)
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid deviceId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Failed to remove trusted device: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                delete("/trusted-devices") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@delete call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    try {
                        mfaService.removeAllTrustedDevices(userId)
                        call.respondText("All trusted devices removed", status = HttpStatusCode.OK)
                    } catch (e: Exception) {
                        call.respondText("Failed to remove trusted devices: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
    }
}
