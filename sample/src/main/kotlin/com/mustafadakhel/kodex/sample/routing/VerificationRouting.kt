package com.mustafadakhel.kodex.sample.routing

import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.authorizedRoute
import com.mustafadakhel.kodex.sample.DefaultRealms
import com.mustafadakhel.kodex.verification.ContactType
import com.mustafadakhel.kodex.verification.VerificationResult
import com.mustafadakhel.kodex.verification.VerificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.setupVerificationRouting() = routing {
    DefaultRealms.forEach { realm ->
        authenticateFor(realm) {
            authorizedRoute("/${realm.name}/verification", KodexId) {
                post("/send") { userId: UUID ->
                    val verificationService = call.extensionService<VerificationService>(realm)
                        ?: return@post call.respondText(
                            "Verification not configured",
                            status = HttpStatusCode.InternalServerError
                        )

                    val params = call.receiveParameters()
                    val contactTypeParam = params["contactType"] ?: return@post call.respondText(
                        "Missing contactType (EMAIL, PHONE, or custom attribute key)",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val contactType = parseContactType(contactTypeParam)
                            ?: return@post call.respondText(
                                "Invalid contactType",
                                status = HttpStatusCode.BadRequest
                            )

                        verificationService.sendVerification(userId, contactType)
                        call.respondText("Verification sent", status = HttpStatusCode.OK)
                    } catch (e: Exception) {
                        call.respondText("Failed to send verification: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }

                post("/verify") { userId: UUID ->
                    val verificationService = call.extensionService<VerificationService>(realm)
                        ?: return@post call.respondText(
                            "Verification not configured",
                            status = HttpStatusCode.InternalServerError
                        )

                    val params = call.receiveParameters()
                    val token = params["token"] ?: return@post call.respondText(
                        "Missing token",
                        status = HttpStatusCode.BadRequest
                    )
                    val contactTypeParam = params["contactType"] ?: return@post call.respondText(
                        "Missing contactType",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val contactType = parseContactType(contactTypeParam)
                            ?: return@post call.respondText(
                                "Invalid contactType",
                                status = HttpStatusCode.BadRequest
                            )

                        val result = verificationService.verifyToken(userId, contactType, token)
                        when (result) {
                            is VerificationResult.Success -> {
                                call.respondText("Verification successful", status = HttpStatusCode.OK)
                            }
                            is VerificationResult.Invalid -> {
                                call.respondText("Invalid or expired token: ${result.reason}", status = HttpStatusCode.BadRequest)
                            }
                            is VerificationResult.RateLimitExceeded -> {
                                call.respondText("Too many attempts: ${result.reason}", status = HttpStatusCode.TooManyRequests)
                            }
                        }
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }


                post("/resend") { userId: UUID ->
                    val verificationService = call.extensionService<VerificationService>(realm)
                        ?: return@post call.respondText(
                            "Verification not configured",
                            status = HttpStatusCode.InternalServerError
                        )

                    val params = call.receiveParameters()
                    val contactTypeParam = params["contactType"] ?: return@post call.respondText(
                        "Missing contactType",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val contactType = parseContactType(contactTypeParam)
                            ?: return@post call.respondText(
                                "Invalid contactType",
                                status = HttpStatusCode.BadRequest
                            )

                        verificationService.resendVerification(userId, contactType)
                        call.respondText("Verification resent", status = HttpStatusCode.OK)
                    } catch (e: Exception) {
                        call.respondText("Failed to resend verification: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }

            route("/${realm.name}/verification") {
                get("/status") {
                    val userId = with(KodexId) { call.idOrFail() }
                    val verificationService = call.extensionService<VerificationService>(realm)
                        ?: return@get call.respondText(
                            "Verification not configured",
                            status = HttpStatusCode.InternalServerError
                        )

                    try {
                        val contacts = verificationService.getUserContacts(userId)
                        call.respond(contacts)
                    } catch (e: Exception) {
                        call.respondText("Failed to get status: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
    }
}

private fun parseContactType(param: String): ContactType? = when (param.uppercase()) {
    "EMAIL" -> ContactType.Email
    "PHONE" -> ContactType.Phone
    else -> {
        if (param.isNotBlank()) ContactType.CustomAttribute(param) else null
    }
}
