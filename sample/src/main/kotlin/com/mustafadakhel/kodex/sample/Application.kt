package com.mustafadakhel.kodex.sample

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.kodex
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

object DefaultRealms {
    val AdminRealm = Realm("admin")
    val UserRealm = Realm("user")
    fun forEach(action: (Realm) -> Unit) {
        action(AdminRealm)
        action(UserRealm)
    }
}

fun main() {
    embeddedServer(Netty, port = 8080) {
        setupAuthentication()
    }.start(wait = true)
}

private fun Application.setupAuthentication() {
    val config = environment.config
    val kodex = install(Kodex) {
        database {
            driverClassName = config.property("db.driver").getString()
            jdbcUrl = config.property("db.jdbcUrl").getString()
            username = config.property("db.username").getString()
            password = config.property("db.password").getString()

            maximumPoolSize = 10
            idleTimeout = 600_000
        }
        DefaultRealms.forEach { realm ->
            realm(realm) {
                claims {
                    issuer("claims-issuer")
                    audience("claims-audience")
                }
                secrets {
                    raw("secret", "secret2", "secret3")
                }
            }
        }
    }

    setupAuthRouting()
}

fun Application.setupAuthRouting() = routing {
    DefaultRealms.forEach { realm ->
        val kodexService = kodex.serviceOf(realm)
        route("/${realm.owner}/auth") {
            post("/register") {
                val params = call.receiveParameters()
                val password = params["password"] ?: return@post call.respondText(
                    "Missing password",
                    status = HttpStatusCode.BadRequest
                )
                val email =
                    params["email"] ?: return@post call.respondText("Missing email", status = HttpStatusCode.BadRequest)

                // or use status-page to handle exceptions globally
                try {
                    kodexService.createUser(
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

                // or use status-page to handle exceptions globally
                try {
                    val tokenPair = when {
                        email != null -> kodexService.tokenByEmail(email, password)
                        phone != null -> kodexService.tokenByPhone(phone, password)
                        else -> throw KodexThrowable.Authorization.InvalidCredentials
                    }
                    call.respond(tokenPair)
                } catch (e: KodexThrowable.Authorization.InvalidCredentials) {
                    call.respondText("Invalid credentials", status = HttpStatusCode.Unauthorized)
                } catch (e: KodexThrowable.Authorization.UnverifiedAccount) {
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

                // or use status-page to handle exceptions globally
                try {
                    val user = kodexService.getUserByEmail(email)
                    val newTokens = kodexService.refresh(user.id, refreshToken)
                    call.respond(newTokens)
                } catch (e: Throwable) {
                    call.respondText("Unable to refresh token", status = HttpStatusCode.Unauthorized)
                }
            }

            post("/verify") {
                val params = call.receiveParameters()
                val email =
                    params["email"] ?: return@post call.respondText("Missing email", status = HttpStatusCode.BadRequest)
                val verified = params["verified"]?.toBoolean() ?: true

                // or use status-page to handle exceptions globally
                try {
                    val user = kodexService.getUserByEmail(email)
                    kodexService.setVerified(user.id, verified)
                    call.respondText("Verification updated", status = HttpStatusCode.OK)
                } catch (e: KodexThrowable.UserNotFound) {
                    call.respondText("User not found: $email", status = HttpStatusCode.NotFound)
                }
            }
        }
    }
}
