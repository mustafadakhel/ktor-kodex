package com.mustafadakhel.kodex.validation

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import io.ktor.utils.io.*

/**
 * Configuration for the validation extension.
 * Provides a type-safe DSL for configuring input validation and sanitization.
 *
 * Example usage:
 * ```kotlin
 * realm("admin") {
 *     validation {
 *         email {
 *             allowDisposable = false
 *         }
 *         phone {
 *             defaultRegion = "US"
 *             requireE164 = true
 *         }
 *         password {
 *             minLength = 12
 *             minScore = 3
 *         }
 *         customAttributes {
 *             maxKeyLength = 128
 *             maxValueLength = 4096
 *             maxAttributes = 50
 *             allowedKeys = setOf("department", "employee_id", "location")
 *         }
 *     }
 * }
 * ```
 */
@KtorDsl
public class ValidationConfig : ExtensionConfig() {

    private val emailConfig = EmailConfigScope()
    private val phoneConfig = PhoneConfigScope()
    private val passwordConfig = PasswordConfigScope()
    private val customAttributesConfig = CustomAttributesConfigScope()

    /**
     * Configure email validation settings.
     */
    public fun email(block: EmailConfigScope.() -> Unit) {
        emailConfig.apply(block)
    }

    /**
     * Configure phone validation settings.
     */
    public fun phone(block: PhoneConfigScope.() -> Unit) {
        phoneConfig.apply(block)
    }

    /**
     * Configure password validation settings.
     */
    public fun password(block: PasswordConfigScope.() -> Unit) {
        passwordConfig.apply(block)
    }

    /**
     * Configure custom attribute validation settings.
     */
    public fun customAttributes(block: CustomAttributesConfigScope.() -> Unit) {
        customAttributesConfig.apply(block)
    }

    override fun build(context: ExtensionContext): ValidationExtension {
        val configImpl = ValidationConfigImpl(
            email = emailConfig.build(),
            phone = phoneConfig.build(),
            password = passwordConfig.build(),
            customAttributes = customAttributesConfig.build()
        )
        val service = validationService(configImpl)
        return ValidationExtension(service)
    }
}

/**
 * DSL scope for email validation configuration.
 */
@KtorDsl
public class EmailConfigScope internal constructor() {

    /**
     * Whether to allow disposable email addresses (e.g., tempmail.com).
     *
     * Default: false
     *
     * Set to true if you want to allow temporary email addresses.
     * Blocking disposable emails helps prevent spam and fake accounts.
     */
    public var allowDisposable: Boolean = false

    internal fun build(): EmailValidationConfig = EmailValidationConfig(
        allowDisposable = allowDisposable
    )
}

/**
 * DSL scope for phone validation configuration.
 */
@KtorDsl
public class PhoneConfigScope internal constructor() {

    /**
     * Default region code for parsing phone numbers without country code.
     *
     * Default: "ZZ" (unknown region)
     *
     * Use ISO 3166-1 alpha-2 country codes (e.g., "US", "GB", "FR").
     * When set to "ZZ", no default region is assumed, and international format is required.
     * This is used when parsing numbers that don't start with +.
     */
    public var defaultRegion: String = "ZZ"

    /**
     * Whether to require E.164 international format (+[country code][number]).
     *
     * Default: true
     *
     * When true, all phone numbers must start with + and country code.
     * Set to false to allow local format numbers (parsed using defaultRegion).
     */
    public var requireE164: Boolean = true

    internal fun build(): PhoneValidationConfig = PhoneValidationConfig(
        defaultRegion = defaultRegion,
        requireE164 = requireE164
    )
}

/**
 * DSL scope for password validation configuration.
 */
@KtorDsl
public class PasswordConfigScope internal constructor() {

    /**
     * Minimum password length in characters.
     *
     * Default: 8
     *
     * NIST recommends minimum 8 characters. Consider 12+ for sensitive systems.
     */
    public var minLength: Int = 8

    /**
     * Minimum password strength score (0-4).
     *
     * Default: 2 (moderate)
     *
     * Score levels:
     * - 0: Very weak (common passwords, simple patterns)
     * - 1: Weak (predictable, low entropy)
     * - 2: Moderate (acceptable for most systems)
     * - 3: Strong (good entropy, no common patterns)
     * - 4: Very strong (excellent entropy, complex)
     */
    public var minScore: Int = 2

    /**
     * Set of common passwords to reject.
     *
     * Default: ~120 most common passwords from breach data
     *
     * You can provide a custom set to extend or replace the default dictionary.
     * For stricter security, consider using a larger dictionary (e.g., top 10k from SecLists).
     */
    public var commonPasswords: Set<String> = CommonPasswords.top10k

    internal fun build(): PasswordValidationConfig = PasswordValidationConfig(
        minLength = minLength,
        minScore = minScore,
        commonPasswords = commonPasswords
    )
}

/**
 * DSL scope for custom attribute validation configuration.
 */
@KtorDsl
public class CustomAttributesConfigScope internal constructor() {

    /**
     * Maximum length for attribute keys.
     *
     * Default: 128
     *
     * Keys are restricted to alphanumeric characters, underscore, hyphen, and dot.
     */
    public var maxKeyLength: Int = 128

    /**
     * Maximum length for attribute values.
     *
     * Default: 4096
     *
     * Values are sanitized for XSS and have control characters removed.
     */
    public var maxValueLength: Int = 4096

    /**
     * Maximum number of custom attributes per user.
     *
     * Default: 50
     *
     * Prevents abuse and resource exhaustion from excessive attributes.
     */
    public var maxAttributes: Int = 50

    /**
     * Optional allowlist of permitted attribute keys.
     *
     * Default: null (all keys allowed)
     *
     * When set, only keys in this set are allowed. Useful for strict schemas.
     * Example: setOf("department", "employee_id", "location")
     */
    public var allowedKeys: Set<String>? = null

    internal fun build(): CustomAttributeValidationConfig = CustomAttributeValidationConfig(
        maxKeyLength = maxKeyLength,
        maxValueLength = maxValueLength,
        maxAttributes = maxAttributes,
        allowedKeys = allowedKeys
    )
}
