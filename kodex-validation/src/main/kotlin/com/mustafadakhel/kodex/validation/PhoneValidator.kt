package com.mustafadakhel.kodex.validation

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Validates phone numbers using Google's libphonenumber library.
 * Supports international E.164 format and regional validation.
 *
 * Configuration:
 * - defaultRegion: Region code for parsing numbers without country code (e.g., "US", "GB", "ZZ")
 * - requireE164: When true, enforces that INPUT must be in E.164 format (starts with +).
 *                When false, accepts local format and normalizes to E.164 for storage.
 *
 * Note: Valid numbers are always normalized to E.164 format in the output, regardless of input format.
 */
internal class PhoneValidator(
    private val defaultRegion: String = "ZZ",
    private val requireE164: Boolean = true
) {
    private val phoneUtil = PhoneNumberUtil.getInstance()
    private val sanitizer = InputSanitizer()

    public fun validate(phone: String, field: String = "phone"): ValidationResult {
        val sanitized = sanitizer.sanitizePhone(phone)
        val errors = mutableListOf<ValidationError>()

        if (sanitized.isEmpty()) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "phone.empty",
                    message = "Phone number cannot be empty"
                )
            )
            return ValidationResult.invalid(errors = errors, originalValue = phone)
        }

        try {
            val phoneNumber = phoneUtil.parse(sanitized, defaultRegion)

            // Check if number is possible (basic length/format check)
            if (!phoneUtil.isPossibleNumber(phoneNumber)) {
                errors.add(
                    ValidationError.of(
                        field = field,
                        code = "phone.impossible",
                        message = "Phone number is not possible",
                        "reason" to phoneUtil.isPossibleNumberWithReason(phoneNumber).toString()
                    )
                )
            }

            // Check if number is valid (deeper validation with regional rules)
            if (!phoneUtil.isValidNumber(phoneNumber)) {
                errors.add(
                    ValidationError.of(
                        field = field,
                        code = "phone.invalid",
                        message = "Phone number is not valid for its region",
                        "region" to phoneUtil.getRegionCodeForNumber(phoneNumber)
                    )
                )
            }

            // Check E.164 format if required
            if (requireE164 && !sanitized.startsWith("+")) {
                errors.add(
                    ValidationError.of(
                        field = field,
                        code = "phone.e164",
                        message = "Phone number must be in E.164 format (start with +)",
                        "suggestion" to phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
                    )
                )
            }

            // Format to E.164 if valid
            val e164 = if (errors.isEmpty()) {
                phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else {
                null
            }

            return if (errors.isEmpty()) {
                ValidationResult.valid(sanitized = e164, originalValue = phone)
            } else {
                ValidationResult.invalid(errors = errors, originalValue = phone)
            }
        } catch (e: NumberParseException) {
            errors.add(
                ValidationError.of(
                    field = field,
                    code = "phone.parse_error",
                    message = "Failed to parse phone number: ${e.errorType}",
                    "error" to e.errorType.toString(),
                    "input" to sanitized
                )
            )
            return ValidationResult.invalid(errors = errors, originalValue = phone)
        }
    }
}
