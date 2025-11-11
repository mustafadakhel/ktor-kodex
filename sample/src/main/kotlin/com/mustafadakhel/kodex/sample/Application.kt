package com.mustafadakhel.kodex.sample

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.audit.audit
import com.mustafadakhel.kodex.audit.DatabaseAuditProvider
import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.kodex
import com.mustafadakhel.kodex.lockout.accountLockout
import com.mustafadakhel.kodex.lockout.AccountLockoutPolicy
import com.mustafadakhel.kodex.metrics.metrics
import com.mustafadakhel.kodex.mfa.MfaService
import com.mustafadakhel.kodex.mfa.mfa
import com.mustafadakhel.kodex.mfa.sender.MfaCodeSender
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.passwordreset.passwordReset
import com.mustafadakhel.kodex.passwordreset.PasswordResetSender
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.authorizedRoute
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.validation.validation
import com.mustafadakhel.kodex.verification.verification
import com.mustafadakhel.kodex.verification.VerificationConfig
import com.mustafadakhel.kodex.verification.VerificationThrowable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
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

                validation {
                    email {
                        allowDisposable = false
                    }
                    phone {
                        defaultRegion = "IQ"
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

                accountLockout {
                    policy = AccountLockoutPolicy(
                        maxFailedAttempts = 5,
                        attemptWindow = 15.minutes,
                        lockoutDuration = 30.minutes
                    )
                }

                audit {
                    provider = DatabaseAuditProvider()
                }

                metrics {
                }

                verification {
                    strategy = VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED
                    defaultTokenExpiration = 24.hours

                    email {
                        required = true
                        autoSend = false
                        tokenExpiration = 24.hours
                    }

                    phone {
                        required = true
                        autoSend = false
                        tokenExpiration = 10.minutes
                    }
                }

                passwordReset(
                    sender = object : PasswordResetSender {
                        override suspend fun send(recipient: String, token: String, expiresAt: String) {
                            println("Password reset token for $recipient: $token (expires: $expiresAt)")
                        }
                    }
                ) {
                    tokenValidity = 15.minutes
                    maxAttemptsPerUser = 5
                    maxAttemptsPerIdentifier = 5
                    maxAttemptsPerIp = 20
                    cooldownPeriod = 1.minutes
                }

                mfa {
                    requireMfa = false

                    emailMfa {
                        sender = object : MfaCodeSender {
                            override suspend fun send(contactValue: String, code: String) {
                                println("MFA code for $contactValue: $code")
                            }
                        }
                    }

                    totpMfa {
                        enabled = true
                        issuer = "KodexSample"
                    }

                    encryption {
                        aesGcm(
                            System.getenv("MFA_ENCRYPTION_KEY")
                                ?: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                        )
                    }
                }
            }
        }
    }

    setupAuthRouting()
    setupMfaRouting()
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

                // or use status-page to handle exceptions globally
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

fun Application.setupMfaRouting() = routing {
    DefaultRealms.forEach { realm ->
        authenticateFor(realm) {
            authorizedRoute("/${realm.owner}/mfa", KodexId) {
                // Enrollment: Email
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

                // Enrollment: TOTP
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
                    val code = params["code"] ?: return@post call.respondText(
                        "Missing code",
                        status = HttpStatusCode.BadRequest
                    )

                    try {
                        val result = mfaService.verifyTotpEnrollment(userId, code)
                        call.respond(result)
                    } catch (e: Exception) {
                        call.respondText("Verification failed: ${e.message}", status = HttpStatusCode.InternalServerError)
                    }
                }
            }

            // GET routes - using manual pattern due to AuthorizedRoute compilation issues
            route("/${realm.owner}/mfa") {
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

            // POST route for method management
            authorizedRoute("/${realm.owner}/mfa", KodexId) {
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

                // Challenge routes
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

                // TOTP verification
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

                // Backup codes
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

            // DELETE routes outside authorizedRoute since AuthorizedRoute doesn't support DELETE
            route("/${realm.owner}/mfa") {
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
