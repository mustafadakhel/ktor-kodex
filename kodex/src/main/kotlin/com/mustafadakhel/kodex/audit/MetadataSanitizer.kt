package com.mustafadakhel.kodex.audit

/**
 * Sanitizes audit event metadata to prevent XSS attacks and redact sensitive information.
 *
 * This utility provides:
 * - HTML/JavaScript escaping for all string values
 * - Automatic redaction of sensitive field names
 * - Safe handling of nested maps and collections
 */
internal object MetadataSanitizer {

    /**
     * Field names that contain sensitive information and should be redacted.
     * Uses lowercase substring matching for flexibility.
     */
    private val SENSITIVE_FIELDS = setOf(
        "password",
        "token",
        "secret",
        "key",
        "credential",
        "authorization",
        "session",
        "csrf",
        "otp",
        "code"
    )

    /**
     * Redaction placeholder for sensitive fields.
     */
    private const val REDACTED = "[REDACTED]"

    /**
     * Sanitizes metadata map by escaping HTML and redacting sensitive fields.
     *
     * @param metadata Raw metadata map
     * @return Sanitized metadata safe for storage and display
     */
    fun sanitize(metadata: Map<String, Any>): Map<String, Any> {
        return metadata.mapValues { (key, value) ->
            if (isSensitiveField(key)) {
                REDACTED
            } else {
                sanitizeValue(value)
            }
        }
    }

    /**
     * Checks if a field name is considered sensitive.
     *
     * Uses substring matching to catch variations like:
     * - password, newPassword, user_password
     * - token, accessToken, refresh_token
     * - secret, api_secret
     * - key, apiKey, private_key (but not "keyboard" or "monkey")
     */
    private fun isSensitiveField(fieldName: String): Boolean {
        val lowerFieldName = fieldName.lowercase()

        // Special handling for "key" to avoid false positives
        if (lowerFieldName.contains("key")) {
            // Only match if "key" appears with common prefixes/suffixes
            return lowerFieldName.matches(Regex(".*(_|api|private|public|secret|access|auth).*key.*")) ||
                    lowerFieldName.matches(Regex(".*key(_|data|value|material).*"))
        }

        return SENSITIVE_FIELDS.any { keyword ->
            keyword != "key" && lowerFieldName.contains(keyword)
        }
    }

    /**
     * Sanitizes a single value by escaping HTML entities.
     */
    private fun sanitizeValue(value: Any?): Any {
        return when (value) {
            is String -> escapeHtml(value)
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") sanitize(value as Map<String, Any>)
            is Collection<*> -> value.map { sanitizeValue(it) }
            null -> ""
            else -> value
        }
    }

    /**
     * Escapes HTML entities to prevent XSS attacks.
     *
     * Replaces the following characters:
     * - & → &amp;
     * - < → &lt;
     * - > → &gt;
     * - " → &quot;
     * - ' → &#x27;
     * - / → &#x2F;
     */
    private fun escapeHtml(input: String): String {
        return buildString(input.length + 16) {
            input.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#x27;")
                    '/' -> append("&#x2F;")
                    else -> append(char)
                }
            }
        }
    }
}
