package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.validation.ValidatableConfig
import com.mustafadakhel.kodex.validation.ValidationResult
import com.mustafadakhel.kodex.validation.validate
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Policy for a verifiable contact type.
 */
public data class ContactVerificationPolicy(
    val identifier: ContactIdentifier,
    val required: Boolean = false,
    val autoSend: Boolean = true,
    val tokenExpiration: Duration? = null,
    val sender: VerificationSender? = null
)

/**
 * Builder for contact verification policies.
 */
public class ContactPolicyBuilder internal constructor(private val identifier: ContactIdentifier) {
    /**
     * Whether verification of this contact is required before login.
     * Default: false
     */
    public var required: Boolean = false

    /**
     * Whether to automatically send verification when the contact is added.
     * Default: true
     */
    public var autoSend: Boolean = true

    /**
     * How long verification tokens remain valid for this contact type.
     * If not specified, uses the global defaultTokenExpiration.
     * Example: 24.hours, 10.minutes
     */
    public var tokenExpiration: Duration? = null

    /**
     * The sender implementation for this contact type.
     * Required for auto-send to work.
     */
    public var sender: VerificationSender? = null

    internal fun build(): ContactVerificationPolicy = ContactVerificationPolicy(
        identifier = identifier,
        required = required,
        autoSend = autoSend,
        tokenExpiration = tokenExpiration,
        sender = sender
    )
}

/**
 * Configuration for the verification extension.
 * Provides a type-safe DSL for configuring contact verification policies.
 *
 * Example usage:
 * ```kotlin
 * realm("admin") {
 *     verification {
 *         strategy = VerificationStrategy.VERIFY_ALL_PROVIDED
 *         defaultTokenExpiration = 24.hours
 *
 *         email {
 *             required = true
 *             autoSend = true
 *             tokenExpiration = 24.hours  // Override default
 *             sender = EmailVerificationSender(emailProvider)
 *         }
 *
 *         phone {
 *             required = false
 *             autoSend = true
 *             tokenExpiration = 10.minutes  // SMS expires faster
 *             sender = SMSVerificationSender(twilioClient)
 *         }
 *
 *         customAttribute("discord") {
 *             required = false
 *             autoSend = false
 *             tokenExpiration = 30.minutes
 *             sender = DiscordVerificationSender(discordBot)
 *         }
 *     }
 * }
 * ```
 */
@KtorDsl
public class VerificationConfig : ExtensionConfig(), ValidatableConfig {

    /**
     * Strategy for determining which contacts to verify.
     */
    public enum class VerificationStrategy {
        /**
         * Verify all contacts the user provided (email, phone, custom attributes).
         */
        VERIFY_ALL_PROVIDED,

        /**
         * Only verify contacts marked as required in policies.
         */
        VERIFY_REQUIRED_ONLY,

        /**
         * Manual control - library user decides what to send.
         */
        MANUAL
    }

    private val policies = mutableMapOf<String, ContactVerificationPolicy>()

    /**
     * Strategy for determining which contacts to verify.
     * Default: VERIFY_ALL_PROVIDED
     */
    public var strategy: VerificationStrategy = VerificationStrategy.VERIFY_ALL_PROVIDED

    /**
     * Default token expiration for all contact types.
     * Individual contact policies can override this.
     * Default: 24 hours
     */
    public var defaultTokenExpiration: Duration = 24.hours

    /**
     * Maximum send attempts per user in the rate limit window.
     * Prevents a single user from spamming verification requests.
     * Default: 5
     */
    public var maxSendAttemptsPerUser: Int = 5

    /**
     * Maximum send attempts per contact value (email/phone) in the rate limit window.
     * Prevents spamming a specific email/phone regardless of which user.
     * Default: 5
     */
    public var maxSendAttemptsPerContact: Int = 5

    /**
     * Maximum send attempts per IP address in the rate limit window.
     * Prevents distributed attacks from a single IP.
     * Default: 10
     */
    public var maxSendAttemptsPerIp: Int = 10

    /**
     * Maximum verification attempts per user+IP combination.
     * Prevents brute force attacks without revealing token existence.
     * Default: 5
     */
    public var maxVerifyAttemptsPerUserIp: Int = 5

    /**
     * Minimum response time for verification operations (milliseconds).
     * Adds constant-time delay to prevent timing attacks.
     * Default: 100ms
     */
    public var minVerificationResponseTimeMs: Long = 100L

    /**
     * Time window for send rate limiting.
     * Default: 15 minutes
     */
    public var sendRateLimitWindow: Duration = 15.minutes

    /**
     * Time window for verification attempt rate limiting.
     * Default: 5 minutes
     */
    public var verifyRateLimitWindow: Duration = 5.minutes

    /**
     * Minimum time between send requests (cooldown period).
     * Prevents users from spamming requests even within the rate limit.
     * Set to null to disable cooldown.
     * Default: null (no cooldown)
     *
     * Example: 30.seconds prevents more than 1 request per 30 seconds
     */
    public var sendCooldownPeriod: Duration? = null

    /**
     * Configure policy for EMAIL contact.
     */
    public fun email(block: ContactPolicyBuilder.() -> Unit) {
        val identifier = ContactIdentifier(ContactType.EMAIL)
        val builder = ContactPolicyBuilder(identifier)
        builder.block()
        policies[identifier.key] = builder.build()
    }

    /**
     * Configure policy for PHONE contact.
     */
    public fun phone(block: ContactPolicyBuilder.() -> Unit) {
        val identifier = ContactIdentifier(ContactType.PHONE)
        val builder = ContactPolicyBuilder(identifier)
        builder.block()
        policies[identifier.key] = builder.build()
    }

    /**
     * Configure policy for a CUSTOM_ATTRIBUTE contact.
     * @param attributeKey The custom attribute key (e.g., "discord", "twitter")
     */
    public fun customAttribute(attributeKey: String, block: ContactPolicyBuilder.() -> Unit) {
        val identifier = ContactIdentifier(ContactType.CUSTOM_ATTRIBUTE, attributeKey)
        val builder = ContactPolicyBuilder(identifier)
        builder.block()
        policies[identifier.key] = builder.build()
    }

    /**
     * Get the policy for a specific contact identifier.
     */
    public fun getPolicy(identifier: ContactIdentifier): ContactVerificationPolicy? = policies[identifier.key]

    /**
     * Get all contact identifiers that are marked as required.
     */
    public fun getRequiredContacts(): List<ContactIdentifier> =
        policies.values.filter { it.required }.map { it.identifier }

    /**
     * Get all configured policies.
     */
    public fun getAllPolicies(): Map<String, ContactVerificationPolicy> = policies.toMap()

    /**
     * Get the effective token expiration for a contact.
     * Returns policy-specific expiration if set, otherwise falls back to default.
     */
    public fun getTokenExpiration(identifier: ContactIdentifier): Duration {
        val policy = getPolicy(identifier)
        return policy?.tokenExpiration ?: defaultTokenExpiration
    }

    /**
     * Get the sender for a contact type.
     */
    public fun getSender(identifier: ContactIdentifier): VerificationSender? {
        return getPolicy(identifier)?.sender
    }

    override fun validate(): ValidationResult = validate {
        // Validate token expiration
        requirePositive(defaultTokenExpiration, "defaultTokenExpiration")

        // Validate rate limits
        requirePositive(maxSendAttemptsPerUser, "maxSendAttemptsPerUser")
        requirePositive(maxSendAttemptsPerContact, "maxSendAttemptsPerContact")
        requirePositive(maxSendAttemptsPerIp, "maxSendAttemptsPerIp")
        requirePositive(maxVerifyAttemptsPerUserIp, "maxVerifyAttemptsPerUserIp")

        // Validate minimum response time
        requireNonNegative(minVerificationResponseTimeMs, "minVerificationResponseTimeMs")
        require(minVerificationResponseTimeMs < 10000) {
            "minVerificationResponseTimeMs should be less than 10 seconds to avoid request timeouts, got: ${minVerificationResponseTimeMs}ms"
        }

        // Validate rate limit windows
        requirePositive(sendRateLimitWindow, "sendRateLimitWindow")
        requirePositive(verifyRateLimitWindow, "verifyRateLimitWindow")

        // Validate cooldown period (optional, but must be reasonable if provided)
        sendCooldownPeriod?.let { cooldown ->
            require(cooldown.isPositive()) {
                "sendCooldownPeriod must be positive if provided, got: $cooldown"
            }
            require(cooldown.inWholeSeconds >= 1) {
                "sendCooldownPeriod should be at least 1 second for practical use, got: $cooldown"
            }
            require(cooldown.inWholeMinutes <= 60) {
                "sendCooldownPeriod should not exceed 1 hour to avoid locking users out, got: $cooldown"
            }
        }

        // Validate policies
        policies.values.forEach { policy ->
            policy.tokenExpiration?.let { expiration ->
                require(expiration.isPositive()) {
                    "Token expiration for ${policy.identifier.key} must be positive, got: $expiration"
                }
            }

            // Warn if autoSend is true but no sender configured
            if (policy.autoSend && policy.sender == null) {
                error("autoSend is true for ${policy.identifier.key} but no sender is configured")
            }

            // Warn if required but no sender configured
            if (policy.required && policy.sender == null && strategy != VerificationStrategy.MANUAL) {
                error("Contact ${policy.identifier.key} is required but no sender is configured (strategy is not MANUAL)")
            }
        }

        // Validate required contacts exist when using VERIFY_REQUIRED_ONLY
        if (strategy == VerificationStrategy.VERIFY_REQUIRED_ONLY) {
            val requiredCount = policies.values.count { it.required }
            require(requiredCount > 0) {
                "Strategy is VERIFY_REQUIRED_ONLY but no contacts are marked as required"
            }
        }
    }

    override fun build(context: ExtensionContext): VerificationExtension {
        // Validate configuration before building
        val validationResult = validate()
        if (!validationResult.isValid()) {
            throw IllegalStateException(
                "VerificationConfig validation failed:\n" +
                validationResult.errors().joinToString("\n") { "  - $it" }
            )
        }

        val service = DefaultVerificationService(
            config = this,
            timeZone = context.timeZone,
            eventBus = context.eventBus,
            realm = context.realm.owner
        )
        val cleanupService = DefaultTokenCleanupService(context.timeZone, context.eventBus, context.realm.owner)
        return VerificationExtension(service, cleanupService, this, context.timeZone)
    }
}
