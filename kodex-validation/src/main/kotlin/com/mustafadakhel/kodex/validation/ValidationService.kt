package com.mustafadakhel.kodex.validation

/**
 * Service for validating user input across email, phone, password, and custom attributes.
 * Provides centralized validation with configurable rules and XSS protection.
 */
public interface ValidationService {
    public fun validateEmail(email: String, field: String = "email"): ValidationResult
    public fun validatePhone(phone: String, field: String = "phone"): ValidationResult
    public fun validatePassword(password: String, field: String = "password"): ValidationResult
    public fun analyzePasswordStrength(password: String): PasswordStrength
    public fun validateCustomAttributes(attributes: Map<String, String>, field: String = "customAttributes"): ValidationResult
    public fun validateCustomAttribute(key: String, value: String, field: String = "attribute"): ValidationResult
    public fun sanitizeHtml(input: String, context: InputContext = InputContext.PLAIN_TEXT): String
}

/**
 * Internal configuration data class for the validation service.
 *
 * @property email Email validation configuration
 * @property phone Phone validation configuration
 * @property password Password validation configuration
 * @property customAttributes Custom attribute validation configuration
 */
internal data class ValidationConfigImpl(
    val email: EmailValidationConfig,
    val phone: PhoneValidationConfig,
    val password: PasswordValidationConfig,
    val customAttributes: CustomAttributeValidationConfig
)

internal data class EmailValidationConfig(
    val allowDisposable: Boolean = false
)

internal data class PhoneValidationConfig(
    val defaultRegion: String = "ZZ",
    val requireE164: Boolean = true
)

internal data class PasswordValidationConfig(
    val minLength: Int = 8,
    val minScore: Int = 2,
    val commonPasswords: Set<String> = CommonPasswords.default
)

internal data class CustomAttributeValidationConfig(
    val maxKeyLength: Int = 128,
    val maxValueLength: Int = 4096,
    val maxAttributes: Int = 50,
    val allowedKeys: Set<String>? = null,
    val attributeRules: Map<String, AttributeRule> = emptyMap()
)

/**
 * Validation rule for a specific custom attribute.
 *
 * @property key Attribute key this rule applies to
 * @property required Whether this attribute is required
 * @property pattern Regex pattern the value must match
 * @property maxLength Maximum length for this attribute's value
 * @property minLength Minimum length for this attribute's value
 * @property allowedValues Set of allowed values for this attribute
 * @property customValidator Custom validation function
 */
internal data class AttributeRule(
    val key: String,
    val required: Boolean = false,
    val pattern: String? = null,
    val maxLength: Int? = null,
    val minLength: Int? = null,
    val allowedValues: Set<String>? = null,
    val customValidator: ((String) -> ValidationResult)? = null
)

/**
 * Default implementation of ValidationService that orchestrates all validators.
 *
 * This implementation:
 * - Validates emails according to RFC 5322
 * - Validates phone numbers using libphonenumber (E.164)
 * - Scores password strength using zxcvbn-inspired algorithm
 * - Sanitizes custom attributes to prevent XSS
 * - Provides detailed validation errors with actionable feedback
 *
 * @property config Validation service configuration
 */
internal class DefaultValidationService(
    private val config: ValidationConfigImpl
) : ValidationService {

    private val emailValidator = EmailValidator(
        allowDisposable = config.email.allowDisposable
    )

    private val phoneValidator = PhoneValidator(
        defaultRegion = config.phone.defaultRegion,
        requireE164 = config.phone.requireE164
    )

    private val passwordValidator = PasswordValidator(
        minLength = config.password.minLength,
        minScore = config.password.minScore,
        commonPasswords = config.password.commonPasswords
    )

    private val customAttributeValidator = CustomAttributeValidator(
        maxKeyLength = config.customAttributes.maxKeyLength,
        maxValueLength = config.customAttributes.maxValueLength,
        maxAttributes = config.customAttributes.maxAttributes,
        allowedKeys = config.customAttributes.allowedKeys,
        attributeRules = config.customAttributes.attributeRules
    )

    private val sanitizer = InputSanitizer(
        maxKeyLength = config.customAttributes.maxKeyLength,
        maxValueLength = config.customAttributes.maxValueLength
    )

    override fun validateEmail(email: String, field: String): ValidationResult {
        return emailValidator.validate(email, field)
    }

    override fun validatePhone(phone: String, field: String): ValidationResult {
        return phoneValidator.validate(phone, field)
    }

    override fun validatePassword(password: String, field: String): ValidationResult {
        return passwordValidator.validate(password, field)
    }

    override fun analyzePasswordStrength(password: String): PasswordStrength {
        return passwordValidator.analyzeStrength(password)
    }

    override fun validateCustomAttributes(attributes: Map<String, String>, field: String): ValidationResult {
        return customAttributeValidator.validate(attributes, field)
    }

    override fun validateCustomAttribute(key: String, value: String, field: String): ValidationResult {
        return customAttributeValidator.validateSingle(key, value, field)
    }

    override fun sanitizeHtml(input: String, context: InputContext): String {
        return sanitizer.sanitizeHtml(input, context)
    }
}

internal fun validationService(config: ValidationConfigImpl): ValidationService {
    return DefaultValidationService(config)
}
