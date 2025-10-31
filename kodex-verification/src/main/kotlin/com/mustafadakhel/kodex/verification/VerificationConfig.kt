package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import io.ktor.utils.io.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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
public class VerificationConfig : ExtensionConfig() {

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

    override fun build(context: ExtensionContext): VerificationExtension {
        val service = DefaultVerificationService(
            config = this,
            timeZone = context.timeZone
        )
        return VerificationExtension(service, this, context.timeZone)
    }
}
