package com.mustafadakhel.kodex.sample.routing

import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.kodex
import com.mustafadakhel.kodex.passwordreset.PasswordResetService
import com.mustafadakhel.kodex.passwordreset.TokenConsumptionResult
import com.mustafadakhel.kodex.passwordreset.TokenVerificationResult
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

fun Application.setupPasswordResetRouting() = routing {
    DefaultRealms.forEach { realm ->
        route("/${realm.name}/password-reset") {
            post("/initiate") {
                val resetService = call.extensionService<PasswordResetService>(realm)
                    ?: return@post call.respondText(
                        "Password reset not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                val params = call.receiveParameters()
                val email = params["email"]
                val phone = params["phone"]

                val (identifier, contactType) = when {
                    email != null -> email to PasswordResetService.ContactType.EMAIL
                    phone != null -> phone to PasswordResetService.ContactType.PHONE
                    else -> return@post call.respondText(
                        "Missing email or phone",
                        status = HttpStatusCode.BadRequest
                    )
                }

                val ipAddress = call.request.local.remoteHost

                try {
                    resetService.initiatePasswordReset(identifier, contactType, ipAddress)
                    call.respondText(
                        "If an account exists with that identifier, a password reset link has been sent",
                        status = HttpStatusCode.OK
                    )
                } catch (e: Exception) {
                    call.respondText(
                        "If an account exists with that identifier, a password reset link has been sent",
                        status = HttpStatusCode.OK
                    )
                }
            }

            post("/verify") {
                val resetService = call.extensionService<PasswordResetService>(realm)
                    ?: return@post call.respondText(
                        "Password reset not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                val params = call.receiveParameters()
                val token = params["token"] ?: return@post call.respondText(
                    "Missing token",
                    status = HttpStatusCode.BadRequest
                )

                try {
                    val result = resetService.verifyResetToken(token)
                    when (result) {
                        is TokenVerificationResult.Valid -> {
                            call.respondText("Token valid", status = HttpStatusCode.OK)
                        }
                        is TokenVerificationResult.Invalid -> {
                            call.respondText("Invalid or expired token: ${result.reason}", status = HttpStatusCode.BadRequest)
                        }
                    }
                } catch (e: Exception) {
                    call.respondText("Token verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                }
            }

            post("/complete") {
                val resetService = call.extensionService<PasswordResetService>(realm)
                    ?: return@post call.respondText(
                        "Password reset not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                val params = call.receiveParameters()
                val token = params["token"] ?: return@post call.respondText(
                    "Missing token",
                    status = HttpStatusCode.BadRequest
                )
                val newPassword = params["newPassword"] ?: return@post call.respondText(
                    "Missing newPassword",
                    status = HttpStatusCode.BadRequest
                )

                try {
                    val result = resetService.consumeResetToken(token)
                    when (result) {
                        is TokenConsumptionResult.Success -> {
                            val services = kodex.servicesOf(realm)
                            services.auth.resetPassword(result.userId, newPassword)

                            call.respondText("Password reset successful", status = HttpStatusCode.OK)
                        }
                        is TokenConsumptionResult.Invalid -> {
                            call.respondText("Invalid or expired token: ${result.reason}", status = HttpStatusCode.BadRequest)
                        }
                    }
                } catch (e: Exception) {
                    call.respondText("Password reset failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                }
            }

        }

        authenticateFor(realm) {
            authorizedRoute("/${realm.name}/password-reset", KodexId) {
                post("/revoke") { userId: UUID ->
                    val resetService = call.extensionService<PasswordResetService>(realm)
                        ?: return@post call.respondText(
                            "Password reset not configured",
                            status = HttpStatusCode.InternalServerError
                        )

                    try {
                        resetService.revokeAllResetTokens(userId)
                        call.respondText("All reset tokens revoked", status = HttpStatusCode.OK)
                    } catch (e: Exception) {
                        call.respondText(
                            "Failed to revoke tokens: ${e.message}",
                            status = HttpStatusCode.InternalServerError
                        )
                    }
                }
            }
        }
    }
}
