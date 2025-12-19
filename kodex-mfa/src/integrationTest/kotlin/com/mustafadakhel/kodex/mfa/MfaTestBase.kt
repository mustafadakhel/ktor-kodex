package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.service.passwordHashingService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.setupMfaTestRoutes(realm: Realm) {
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

                    try {
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
                    } catch (e: Exception) {
                        call.respondText("Enrollment failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/enroll/email/verify") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val mfaService = call.extensionService<MfaService>(realm)
                        ?: return@post call.respondText("MFA not configured", status = HttpStatusCode.InternalServerError)

                    val params = call.receiveParameters()
                    val challengeIdStr = params["challengeId"] ?: return@post call.respondText("Missing challengeId", status = HttpStatusCode.BadRequest)
                    val code = params["code"] ?: return@post call.respondText("Missing code", status = HttpStatusCode.BadRequest)

                    try {
                        val challengeId = UUID.fromString(challengeIdStr)
                        when (val result = mfaService.verifyEmailEnrollment(userId, challengeId, code)) {
                            is EnrollmentVerificationResult.Success -> call.respond(
                                mapOf("type" to "Success", "backupCodes" to result.backupCodes)
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
                    } catch (e: IllegalArgumentException) {
                        call.respondText("Invalid challengeId format", status = HttpStatusCode.BadRequest)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

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
            }
        }
    }
}

fun Application.installMfaTest(realm: Realm, emailSender: MockEmailSender, dbName: String): Kodex {
    install(ContentNegotiation) {
        json()
    }

    return install(Kodex) {
        database {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;"
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
}
