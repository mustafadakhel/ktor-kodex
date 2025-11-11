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

    /** Allow disposable email addresses (default: false) */
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

    /** Default region for phone parsing, ISO 3166-1 alpha-2 (default: "ZZ") */
    public var defaultRegion: String = "ZZ"

    /** Require E.164 international format with + prefix (default: true) */
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

    /** Minimum password length (default: 8) */
    public var minLength: Int = 8

    /** Minimum strength score, 0-4 scale (default: 2) */
    public var minScore: Int = 2

    /** Common passwords to reject (default: ~170 from breach data) */
    public var commonPasswords: Set<String> = CommonPasswords.default

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

    /** Maximum length for attribute keys (default: 128) */
    public var maxKeyLength: Int = 128

    /** Maximum length for attribute values (default: 4096) */
    public var maxValueLength: Int = 4096

    /** Maximum number of custom attributes per user (default: 50) */
    public var maxAttributes: Int = 50

    /** Optional allowlist of permitted keys (default: null, allows all) */
    public var allowedKeys: Set<String>? = null

    /**
     * Per-attribute validation rules.
     */
    private val attributeRules = mutableMapOf<String, AttributeRule>()

    /**
     * Configure validation rules for a specific custom attribute.
     *
     * Example:
     * ```kotlin
     * attribute("department") {
     *     required = true
     *     pattern = "^(Engineering|Sales|Marketing)$"
     *     maxLength = 50
     * }
     *
     * attribute("employee_id") {
     *     required = true
     *     pattern = "^EMP\\d{6}$"
     * }
     *
     * attribute("location") {
     *     allowedValues = setOf("US", "UK", "FR", "DE")
     * }
     * ```
     */
    public fun attribute(key: String, block: AttributeRuleScope.() -> Unit) {
        val scope = AttributeRuleScope(key)
        scope.apply(block)
        attributeRules[key] = scope.build()
    }

    internal fun build(): CustomAttributeValidationConfig = CustomAttributeValidationConfig(
        maxKeyLength = maxKeyLength,
        maxValueLength = maxValueLength,
        maxAttributes = maxAttributes,
        allowedKeys = allowedKeys,
        attributeRules = attributeRules
    )
}

/**
 * DSL scope for configuring validation rules for a specific custom attribute.
 */
@KtorDsl
public class AttributeRuleScope internal constructor(
    private val key: String
) {
    /** Whether this attribute is required (default: false) */
    public var required: Boolean = false

    /** Regex pattern the value must match (default: null) */
    public var pattern: String? = null

    /** Maximum value length, overrides global setting (default: null) */
    public var maxLength: Int? = null

    /** Minimum value length (default: null) */
    public var minLength: Int? = null

    /** Allowed values for this attribute (default: null, allows all) */
    public var allowedValues: Set<String>? = null

    /** Custom validator returning ValidationResult (default: null) */
    public var customValidator: ((String) -> ValidationResult)? = null

    internal fun build(): AttributeRule = AttributeRule(
        key = key,
        required = required,
        pattern = pattern,
        maxLength = maxLength,
        minLength = minLength,
        allowedValues = allowedValues,
        customValidator = customValidator
    )
}
