package com.mustafadakhel.kodex.sample.routing

import com.mustafadakhel.kodex.kodex
import com.mustafadakhel.kodex.sample.DefaultRealms
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.verification.VerificationThrowable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.setupAuthRouting() = routing {
    DefaultRealms.forEach { realm ->
        val services = kodex.servicesOf(realm)
        route("/${realm.owner}/auth") {
            post("/register") {
                val params = call.receiveParameters()
                val password = params["password"] ?: return@post call.respondText(
                    "Missing password",
                    status = HttpStatusCode.BadRequest
                )
                val email =
                    params["email"] ?: return@post call.respondText("Missing email", status = HttpStatusCode.BadRequest)

                try {
                    services.users.createUser(
                        email = email,
                        phone = null,
                        password = password,
                        customAttributes = null,
                        profile = null
                    )
                    call.respondText("User registered", status = HttpStatusCode.Created)
                } catch (e: KodexThrowable.EmailAlreadyExists) {
                    call.respondText("User already exists with email: $email", status = HttpStatusCode.Conflict)
                } catch (e: KodexThrowable.RoleNotFound) {
                    call.respondText("Invalid realm configuration", status = HttpStatusCode.InternalServerError)
                }
            }

            post("/login") {
                val params = call.receiveParameters()
                val email = params["email"]
                val phone = params["phone"]
                val password = params["password"] ?: return@post call.respondText(
                    "Missing password",
                    status = HttpStatusCode.BadRequest
                )

                try {
                    val ipAddress = call.request.local.remoteHost
                    val userAgent = call.request.userAgent()

                    val tokenPair = when {
                        email != null -> services.auth.login(email, password, ipAddress, userAgent)
                        phone != null -> services.auth.loginByPhone(phone, password, ipAddress, userAgent)
                        else -> throw KodexThrowable.Authorization.InvalidCredentials
                    }
                    call.respond(tokenPair)
                } catch (e: KodexThrowable.Authorization.InvalidCredentials) {
                    call.respondText("Invalid credentials", status = HttpStatusCode.Unauthorized)
                } catch (e: VerificationThrowable.UnverifiedAccount) {
                    call.respondText("Account not verified", status = HttpStatusCode.Forbidden)
                }
            }

            post("/refresh") {
                val params = call.receiveParameters()
                val email =
                    params["email"] ?: return@post call.respondText("Missing email", status = HttpStatusCode.BadRequest)
                val refreshToken =
                    params["refresh_token"] ?: return@post call.respondText(
                        "Missing refresh_token",
                        status = HttpStatusCode.BadRequest
                    )

                try {
                    val user = services.users.getUserByEmail(email)
                    val newTokens = services.tokens.refresh(user.id, refreshToken)
                    call.respond(newTokens)
                } catch (e: Throwable) {
                    call.respondText("Unable to refresh token", status = HttpStatusCode.Unauthorized)
                }
            }

        }
    }
}
