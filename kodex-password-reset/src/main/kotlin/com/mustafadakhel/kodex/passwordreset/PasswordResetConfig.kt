package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.validation.ConfigValidationResult
import com.mustafadakhel.kodex.validation.ValidatableConfig
import com.mustafadakhel.kodex.validation.validate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

public class PasswordResetConfig : ExtensionConfig(), ValidatableConfig {

    /** How long reset tokens remain valid (default: 1 hour) */
    public var tokenValidity: Duration = 1.hours

    /** Maximum reset attempts per user in the rate limit window (default: 3) */
    public var maxAttemptsPerUser: Int = 3

    /** Maximum reset attempts per identifier in the rate limit window (default: 5) */
    public var maxAttemptsPerIdentifier: Int = 5

    /** Maximum reset attempts per IP address in the rate limit window (default: 10) */
    public var maxAttemptsPerIp: Int = 10

    /** Time window for rate limiting (default: 15 minutes) */
    public var rateLimitWindow: Duration = 15.minutes

    /** Minimum time between password reset requests, null to disable cooldown (default: null) */
    public var cooldownPeriod: Duration? = null

    public var passwordResetSender: PasswordResetSender? = null

    override fun validate(): ConfigValidationResult = validate {
        // Validate token validity
        requirePositive(tokenValidity, "tokenValidity")
        require(tokenValidity.inWholeMinutes >= 5) {
            "tokenValidity should be at least 5 minutes for usability, got: $tokenValidity"
        }
        require(tokenValidity.inWholeHours <= 24) {
            "tokenValidity should not exceed 24 hours for security, got: $tokenValidity"
        }

        // Validate rate limits
        requirePositive(maxAttemptsPerUser, "maxAttemptsPerUser")
        requirePositive(maxAttemptsPerIdentifier, "maxAttemptsPerIdentifier")
        requirePositive(maxAttemptsPerIp, "maxAttemptsPerIp")

        requirePositive(rateLimitWindow, "rateLimitWindow")

        cooldownPeriod?.let { cooldown ->
            require(cooldown.isPositive()) {
                "cooldownPeriod must be positive if provided, got: $cooldown"
            }
            require(cooldown.inWholeSeconds >= 1) {
                "cooldownPeriod should be at least 1 second for practical use, got: $cooldown"
            }
            require(cooldown.inWholeMinutes <= 60) {
                "cooldownPeriod should not exceed 1 hour to avoid locking users out, got: $cooldown"
            }
        }

        // Validate sender is configured
        requireNotNull(passwordResetSender, "passwordResetSender")
    }

    override fun build(context: ExtensionContext): PasswordResetExtension {
        // Validate configuration before building
        val validationResult = validate()
        if (!validationResult.isValid()) {
            throw IllegalStateException(
                "PasswordResetConfig validation failed:\n" +
                validationResult.errors().joinToString("\n") { "  - $it" }
            )
        }

        val sender = passwordResetSender
            ?: throw IllegalStateException("PasswordResetSender must be configured for password reset")

        val config = PasswordResetConfigData(
            tokenValidity = tokenValidity,
            rateLimit = RateLimitConfigData(
                maxAttemptsPerUser = maxAttemptsPerUser,
                maxAttemptsPerIdentifier = maxAttemptsPerIdentifier,
                maxAttemptsPerIp = maxAttemptsPerIp,
                window = rateLimitWindow,
                cooldown = cooldownPeriod
            )
        )

        val service = DefaultPasswordResetService(
            config = config,
            passwordResetSender = sender,
            timeZone = context.timeZone,
            eventBus = context.eventBus,
            realm = context.realm.name,
            rateLimiter = context.rateLimiter
        )
        val cleanupService = DefaultTokenCleanupService(context.timeZone, context.eventBus, context.realm.name)

        return PasswordResetExtension(service, cleanupService, context.timeZone)
    }
}

internal data class PasswordResetConfigData(
    val tokenValidity: Duration,
    val rateLimit: RateLimitConfigData
)

internal data class RateLimitConfigData(
    val maxAttemptsPerUser: Int,
    val maxAttemptsPerIdentifier: Int,
    val maxAttemptsPerIp: Int,
    val window: Duration,
    val cooldown: Duration?
)
