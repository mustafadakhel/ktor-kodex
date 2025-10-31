package com.mustafadakhel.kodex.sample

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.audit.audit
import com.mustafadakhel.kodex.audit.DatabaseAuditProvider
import com.mustafadakhel.kodex.kodex
import com.mustafadakhel.kodex.lockout.accountLockout
import com.mustafadakhel.kodex.lockout.AccountLockoutPolicy
import com.mustafadakhel.kodex.metrics.metrics
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.validation.validation
import com.mustafadakhel.kodex.verification.verification
import com.mustafadakhel.kodex.verification.VerificationConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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

                // Configure validation extension
                validation {
                    email {
                        allowDisposable = false
                    }
                    phone {
                        defaultRegion = "US"
                        requireE164 = true
                    }
                    password {
                        minLength = 12
                        minScore = 3
                    }
                    customAttributes {
                        maxKeyLength = 128
                        maxValueLength = 4096
                        maxAttributes = 50
                    }
                }

                // Configure account lockout extension
                accountLockout {
                    policy = AccountLockoutPolicy(
                        maxFailedAttempts = 5,
                        attemptWindow = 15.minutes,
                        lockoutDuration = 30.minutes
                    )
                }

                // Configure audit logging extension
                audit {
                    provider = DatabaseAuditProvider()
                }

                // Configure metrics extension (uses SimpleMeterRegistry by default)
                metrics {
                    // registry = SimpleMeterRegistry() // default
                }

                verification {
                    strategy = VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED
                    defaultTokenExpiration = 24.hours

                    email {
                        required = true
                        autoSend = false  // Set to false until sender is implemented
                        tokenExpiration = 24.hours
                        // sender = EmailVerificationSender(emailProvider)  // TODO: Implement email sender
                    }

                    phone {
                        required = true
                        autoSend = false  // Set to false until sender is implemented
                        tokenExpiration = 10.minutes  // SMS should expire faster
                        // sender = SMSVerificationSender(twilioClient)  // TODO: Implement SMS sender
                    }

                    // Example: Custom attribute verification
                    // customAttribute("discord") {
                    //     required = false
                    //     autoSend = false
                    //     tokenExpiration = 30.minutes
                    //     sender = DiscordVerificationSender(discordBot)
                    // }
                }
            }
        }
    }

    setupAuthRouting()
}

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

                // or use status-page to handle exceptions globally
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

                // or use status-page to handle exceptions globally
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
                    val user = services.users.getUserByEmail(email)
                    val newTokens = services.tokens.refresh(user.id, refreshToken)
                    call.respond(newTokens)
                } catch (e: Throwable) {
                    call.respondText("Unable to refresh token", status = HttpStatusCode.Unauthorized)
                }
            }

            post("/verify") {
                val params = call.receiveParameters()
                val email =
                    params["email"] ?: return@post call.respondText("Missing email", status = HttpStatusCode.BadRequest)
                // Verification functionality moved to kodex-verification extension
                // TODO: Update this endpoint once verification extension is implemented
                call.respondText("Verification feature requires kodex-verification extension", status = HttpStatusCode.NotImplemented)
            }
        }
    }
}
