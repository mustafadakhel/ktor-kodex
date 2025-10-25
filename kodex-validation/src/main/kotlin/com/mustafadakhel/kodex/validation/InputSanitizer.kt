package com.mustafadakhel.kodex.validation

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.util.Locale

/**
 * Sanitizes user input to prevent XSS and injection attacks.
 * Uses context-aware sanitization strategies based on where the input will be used.
 */
internal class InputSanitizer(
    private val maxKeyLength: Int = 128,
    private val maxValueLength: Int = 4096
) {
    private val safelist = Safelist.none()

    /**
     * Removes all HTML tags from input using Jsoup's Safelist.none().
     */
    public fun sanitizeHtml(input: String, context: InputContext = InputContext.PLAIN_TEXT): String {
        return when (context) {
            InputContext.HTML -> Jsoup.clean(input, safelist)
            InputContext.ATTRIBUTE -> escapeHtml(input)
            InputContext.PLAIN_TEXT -> escapeHtml(input)
        }
    }

    /**
     * Escapes HTML entities to prevent XSS in HTML contexts.
     */
    public fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
    }

    /**
     * Sanitizes custom attribute key-value pairs with length limits and control character removal.
     */
    public fun sanitizeCustomAttribute(key: String, value: String): Pair<String, String> {
        val cleanKey = sanitizeKey(key)
        val cleanValue = sanitizeValue(value)
        return cleanKey to cleanValue
    }

    private fun sanitizeKey(key: String): String {
        val normalized = key.trim()
            .take(maxKeyLength)
            .filter { it.isLetterOrDigit() || it in setOf('_', '-', '.') }

        require(normalized.isNotEmpty()) { "Key cannot be empty after sanitization" }
        return normalized
    }

    private fun sanitizeValue(value: String): String {
        val normalized = value.trim()
            .take(maxValueLength)
            .removeControlCharacters()

        return escapeHtml(normalized)
    }

    /**
     * Removes all ISO control characters including newlines, carriage returns, and tabs.
     * This prevents CRLF injection and other control character attacks.
     */
    private fun String.removeControlCharacters(): String {
        return this.filter { char -> !char.isISOControl() }
    }

    /**
     * Sanitizes email addresses for safe storage and display.
     * Uses Locale.ROOT for consistent case folding across all locales.
     * Note: Does not truncate - length validation is performed by EmailValidator.
     */
    public fun sanitizeEmail(email: String): String {
        return email.trim().lowercase(Locale.ROOT)
    }

    /**
     * Sanitizes phone numbers by removing all non-digit characters except + prefix.
     */
    public fun sanitizePhone(phone: String): String {
        val trimmed = phone.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }

        return if (hasPlus) "+$digits" else digits
    }
}
