package com.mustafadakhel.kodex.sample

import com.mustafadakhel.kodex.Kodex
import com.mustafadakhel.kodex.audit.audit
import com.mustafadakhel.kodex.audit.DatabaseAuditProvider
import com.mustafadakhel.kodex.lockout.accountLockout
import com.mustafadakhel.kodex.lockout.AccountLockoutPolicy
import com.mustafadakhel.kodex.metrics.metrics
import com.mustafadakhel.kodex.mfa.mfa
import com.mustafadakhel.kodex.mfa.sender.MfaCodeSender
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.passwordreset.passwordReset
import com.mustafadakhel.kodex.passwordreset.PasswordResetSender
import com.mustafadakhel.kodex.ratelimit.inmemory.InMemoryRateLimiter
import com.mustafadakhel.kodex.sample.routing.setupAuthRouting
import com.mustafadakhel.kodex.sample.routing.setupMfaRouting
import com.mustafadakhel.kodex.sample.routing.setupPasswordResetRouting
import com.mustafadakhel.kodex.sample.routing.setupVerificationRouting
import com.mustafadakhel.kodex.validation.validation
import com.mustafadakhel.kodex.verification.verification
import com.mustafadakhel.kodex.verification.VerificationConfig
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
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
    install(Kodex) {
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

                // Rate limiter examples:
                // - NoOpRateLimiter (default)
                // - InMemoryRateLimiter (single instance)
                // - RedisRateLimiter (distributed)
                when (realm) {
                    DefaultRealms.AdminRealm -> {
                        // No rate limiting
                    }
                    DefaultRealms.UserRealm -> {
                        rateLimiter(InMemoryRateLimiter(
                            maxEntries = 100_000,
                            cleanupAge = 5.minutes
                        ))
                    }
                }

                // Redis example (requires kodex-ratelimit-redis dependency):
                // val redisClient = RedisClient.create("redis://localhost:6379")
                // val circuitBreaker = CircuitBreaker(5, 30.seconds, 3)
                // rateLimiter(RedisRateLimiter(
                //     connection = redisClient.connect(),
                //     keyPrefix = "kodex:ratelimit:",
                //     circuitBreaker = circuitBreaker,
                //     fallbackRateLimiter = InMemoryRateLimiter()
                // ))

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
    setupVerificationRouting()
    setupPasswordResetRouting()
}
