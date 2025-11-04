package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.validation.ValidatableConfig
import com.mustafadakhel.kodex.validation.ValidationResult
import com.mustafadakhel.kodex.validation.validate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for password reset functionality.
 */
public class PasswordResetConfig : ExtensionConfig(), ValidatableConfig {

    /**
     * How long reset tokens remain valid.
     * Default: 1 hour
     */
    public var tokenValidity: Duration = 1.hours

    /**
     * Maximum reset attempts per user in the rate limit window.
     * Default: 3
     */
    public var maxAttemptsPerUser: Int = 3

    /**
     * Maximum reset attempts per identifier (email/phone) in the rate limit window.
     * Default: 5
     */
    public var maxAttemptsPerIdentifier: Int = 5

    /**
     * Maximum reset attempts per IP address in the rate limit window.
     * Default: 10
     */
    public var maxAttemptsPerIp: Int = 10

    /**
     * Time window for rate limiting.
     * Default: 15 minutes
     */
    public var rateLimitWindow: Duration = 15.minutes

    /**
     * Minimum time between password reset requests (cooldown period).
     * Prevents users from spamming requests even within the rate limit.
     * Set to null to disable cooldown.
     * Default: null (no cooldown)
     *
     * Example: 30.seconds prevents more than 1 request per 30 seconds
     */
    public var cooldownPeriod: Duration? = null

    /**
     * Sender for password reset notifications (email, SMS, etc.).
     */
    public var passwordResetSender: PasswordResetSender? = null

    override fun validate(): ValidationResult = validate {
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

        // Validate rate limit window
        requirePositive(rateLimitWindow, "rateLimitWindow")

        // Validate cooldown period (optional, but must be reasonable if provided)
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
            realm = context.realm.owner
        )
        val cleanupService = DefaultTokenCleanupService(context.timeZone, context.eventBus, context.realm.owner)

        return PasswordResetExtension(service, cleanupService, context.timeZone)
    }
}

/**
 * Immutable configuration data for password reset.
 */
internal data class PasswordResetConfigData(
    val tokenValidity: Duration,
    val rateLimit: RateLimitConfigData
)

/**
 * Immutable rate limit configuration data.
 */
internal data class RateLimitConfigData(
    val maxAttemptsPerUser: Int,
    val maxAttemptsPerIdentifier: Int,
    val maxAttemptsPerIp: Int,
    val window: Duration,
    val cooldown: Duration?
)
