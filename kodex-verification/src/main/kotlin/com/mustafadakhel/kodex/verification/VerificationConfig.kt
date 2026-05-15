package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.ratelimit.NoOpRateLimiter
import com.mustafadakhel.kodex.tokens.token.HexFormat
import com.mustafadakhel.kodex.tokens.token.NumericFormat
import com.mustafadakhel.kodex.tokens.token.TokenFormat
import com.mustafadakhel.kodex.validation.ConfigValidationResult
import com.mustafadakhel.kodex.validation.ValidatableConfig
import com.mustafadakhel.kodex.validation.validate
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

public data class ContactVerificationPolicy(
    val contactType: ContactType,
    val required: Boolean = false,
    val autoSend: Boolean = true,
    val tokenExpiration: Duration? = null,
    val sender: VerificationSender? = null,
    val tokenFormat: TokenFormat<String> = HexFormat(),
    val dependsOn: List<ContactType> = emptyList()
)

public class ContactPolicyBuilder internal constructor(private val contactType: ContactType) {
    /** Whether verification of this contact is required before login (default: false) */
    public var required: Boolean = false

    /** Whether to automatically send verification when the contact is added (default: true) */
    public var autoSend: Boolean = true

    /** How long verification tokens remain valid for this contact type (default: uses global defaultTokenExpiration) */
    public var tokenExpiration: Duration? = null

    /** The sender implementation for this contact type (required for auto-send to work) */
    public var sender: VerificationSender? = null

    /** Token format for this contact type. Defaults to NumericFormat(6) for phone, HexFormat() for others. */
    public var tokenFormat: TokenFormat<String>? = null

    private val _dependsOn = mutableListOf<ContactType>()

    public fun dependsOn(type: ContactType) {
        _dependsOn += type
    }

    internal fun build(): ContactVerificationPolicy {
        val resolvedFormat = tokenFormat ?: when (contactType) {
            is ContactType.Phone -> NumericFormat(6)
            else -> HexFormat()
        }
        return ContactVerificationPolicy(
            contactType = contactType,
            required = required,
            autoSend = autoSend,
            tokenExpiration = tokenExpiration,
            sender = sender,
            tokenFormat = resolvedFormat,
            dependsOn = _dependsOn.toList()
        )
    }
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
 *             tokenExpiration = 24.hours
 *             sender = EmailVerificationSender(emailProvider)
 *         }
 *
 *         phone {
 *             required = false
 *             autoSend = false
 *             tokenExpiration = 10.minutes
 *             sender = SMSVerificationSender(twilioClient)
 *             dependsOn(ContactType.Email)
 *         }
 *
 *         customAttribute("discord") {
 *             required = false
 *             autoSend = false
 *             tokenExpiration = 30.minutes
 *             sender = DiscordVerificationSender(discordBot)
 *             dependsOn(ContactType.CustomAttribute("telegram"))
 *         }
 *     }
 * }
 * ```
 */
@KtorDsl
public class VerificationConfig : ExtensionConfig(), ValidatableConfig {

    /** Strategy for determining which contacts to verify */
    public enum class VerificationStrategy {
        /** Verify all contacts the user provided (email, phone, custom attributes) */
        VERIFY_ALL_PROVIDED,

        /** Only verify contacts marked as required in policies */
        VERIFY_REQUIRED_ONLY,

        /** Manual control - library user decides what to send */
        MANUAL
    }

    private val policies = mutableMapOf<String, ContactVerificationPolicy>()

    /** Strategy for determining which contacts to verify (default: VERIFY_ALL_PROVIDED) */
    public var strategy: VerificationStrategy = VerificationStrategy.VERIFY_ALL_PROVIDED

    /** Default token expiration for all contact types, can be overridden per-policy (default: 24 hours) */
    public var defaultTokenExpiration: Duration = 24.hours

    /** Maximum send attempts per user in the rate limit window (default: 5) */
    public var maxSendAttemptsPerUser: Int = 5

    /** Maximum send attempts per contact value in the rate limit window (default: 5) */
    public var maxSendAttemptsPerContact: Int = 5

    /** Maximum send attempts per IP address in the rate limit window (default: 10) */
    public var maxSendAttemptsPerIp: Int = 10

    /** Maximum verification attempts per user+IP combination to prevent brute force (default: 5) */
    public var maxVerifyAttemptsPerUserIp: Int = 5

    /** Minimum response time for verification operations in milliseconds to prevent timing attacks (default: 100ms) */
    public var minVerificationResponseTimeMs: Long = 100L

    /** Time window for send rate limiting (default: 15 minutes) */
    public var sendRateLimitWindow: Duration = 15.minutes

    /** Time window for verification attempt rate limiting (default: 5 minutes) */
    public var verifyRateLimitWindow: Duration = 5.minutes

    /** Minimum time between send requests, null to disable cooldown (default: null) */
    public var sendCooldownPeriod: Duration? = null

    public fun email(block: ContactPolicyBuilder.() -> Unit) {
        val type = ContactType.Email
        val builder = ContactPolicyBuilder(type)
        builder.block()
        policies[type.key] = builder.build()
    }

    public fun phone(block: ContactPolicyBuilder.() -> Unit) {
        val type = ContactType.Phone
        val builder = ContactPolicyBuilder(type)
        builder.block()
        policies[type.key] = builder.build()
    }

    public fun customAttribute(attributeKey: String, block: ContactPolicyBuilder.() -> Unit) {
        val type = ContactType.CustomAttribute(attributeKey)
        val builder = ContactPolicyBuilder(type)
        builder.block()
        policies[type.key] = builder.build()
    }

    public fun getPolicy(contactType: ContactType): ContactVerificationPolicy? = policies[contactType.key]

    public fun getRequiredContacts(): List<ContactType> =
        policies.values.filter { it.required }.map { it.contactType }

    public fun getAllPolicies(): Map<String, ContactVerificationPolicy> = policies.toMap()

    /** Get the effective token expiration for a contact (policy-specific or default) */
    public fun getTokenExpiration(contactType: ContactType): Duration {
        val policy = getPolicy(contactType)
        return policy?.tokenExpiration ?: defaultTokenExpiration
    }

    public fun getSender(contactType: ContactType): VerificationSender? {
        return getPolicy(contactType)?.sender
    }

    override fun validate(): ConfigValidationResult = validate {
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
            val policyKey = policy.contactType.key

            policy.tokenExpiration?.let { expiration ->
                require(expiration.isPositive()) {
                    "Token expiration for $policyKey must be positive, got: $expiration"
                }
            }

            // Warn if autoSend is true but no sender configured
            if (policy.autoSend && policy.sender == null) {
                error("autoSend is true for $policyKey but no sender is configured")
            }

            // Warn if required but no sender configured
            if (policy.required && policy.sender == null && strategy != VerificationStrategy.MANUAL) {
                error("Contact $policyKey is required but no sender is configured (strategy is not MANUAL)")
            }

            // Validate sender availability for dependency targets
            if (policy.required && policy.dependsOn.isNotEmpty() && strategy != VerificationStrategy.MANUAL) {
                policy.dependsOn.forEach { dep ->
                    val depPolicy = policies[dep.key]
                    if (depPolicy != null && depPolicy.sender == null) {
                        error("Required contact $policyKey depends on ${dep.key}, but ${dep.key} has no sender configured")
                    }
                }
            }

            // Validate autoSend + dependsOn contradiction
            if (policy.autoSend && policy.dependsOn.isNotEmpty()) {
                error(
                    "Contact $policyKey has autoSend=true but also has dependencies. " +
                    "Auto-send cannot work when dependencies must be verified first. Set autoSend=false explicitly."
                )
            }

            // Validate no self-dependency
            policy.dependsOn.forEach { dep ->
                require(dep.key != policyKey) {
                    "Contact $policyKey cannot depend on itself"
                }
                require(policies.containsKey(dep.key)) {
                    "Contact $policyKey depends on ${dep.key} which is not configured"
                }
            }

            // Validate minimum token entropy
            val format = policy.tokenFormat
            if (format is NumericFormat) {
                require(format.length >= 4) {
                    "Token format for $policyKey uses NumericFormat with length ${format.length}, minimum is 4"
                }
            }
            if (format is HexFormat) {
                require(format.length >= 4) {
                    "Token format for $policyKey uses HexFormat with length ${format.length}, minimum is 4"
                }
            }
        }

        // Validate no circular dependencies
        val cycleError = findCycleError()
        if (cycleError != null) {
            error(cycleError)
        }

        // Validate required contacts exist when using VERIFY_REQUIRED_ONLY
        if (strategy == VerificationStrategy.VERIFY_REQUIRED_ONLY) {
            val requiredCount = policies.values.count { it.required }
            require(requiredCount > 0) {
                "Strategy is VERIFY_REQUIRED_ONLY but no contacts are marked as required"
            }
        }
    }

    private fun findCycleError(): String? {
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun dfs(key: String, path: List<String>): String? {
            if (key in stack) {
                val cycle = path.dropWhile { it != key } + key
                return "Circular dependency detected: ${cycle.joinToString(" -> ")}"
            }
            if (key in visited) return null
            stack += key
            for (dep in policies[key]?.dependsOn.orEmpty()) {
                val result = dfs(dep.key, path + key)
                if (result != null) return result
            }
            stack -= key
            visited += key
            return null
        }

        for (key in policies.keys) {
            val result = dfs(key, emptyList())
            if (result != null) return result
        }
        return null
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

        val hasNumericFormat = policies.values.any { it.tokenFormat is NumericFormat }
        if (hasNumericFormat && context.rateLimiter is NoOpRateLimiter) {
            throw IllegalStateException(
                "NumericFormat tokens are vulnerable to brute-force attacks. " +
                "Configure a real rate limiter (InMemoryRateLimiter or RedisRateLimiter) " +
                "when using NumericFormat for verification tokens."
            )
        }

        return VerificationExtension(
            config = this,
            timeZone = context.timeZone,
            eventBus = context.eventBus,
            realm = context.realm.name,
            rateLimiter = context.rateLimiter
        )
    }
}
