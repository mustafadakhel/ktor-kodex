package com.mustafadakhel.kodex.verification

/**
 * Strictly typed contact types supported by the verification system.
 */
public enum class ContactType {
    /**
     * Email address contact.
     */
    EMAIL,

    /**
     * Phone number contact.
     */
    PHONE,

    /**
     * Custom attribute defined by library user.
     * Requires a customAttributeKey to identify which attribute.
     */
    CUSTOM_ATTRIBUTE
}

/**
 * Full identifier for a verifiable contact.
 *
 * For EMAIL/PHONE: just the type is sufficient
 * For CUSTOM_ATTRIBUTE: requires the attribute key (e.g., "discord", "twitter")
 *
 * Examples:
 * ```kotlin
 * ContactIdentifier(ContactType.EMAIL)
 * ContactIdentifier(ContactType.PHONE)
 * ContactIdentifier(ContactType.CUSTOM_ATTRIBUTE, "discord")
 * ```
 */
public data class ContactIdentifier(
    val type: ContactType,
    val customAttributeKey: String? = null
) {
    init {
        require(type != ContactType.CUSTOM_ATTRIBUTE || customAttributeKey != null) {
            "customAttributeKey is required for CUSTOM_ATTRIBUTE type"
        }
        require(type == ContactType.CUSTOM_ATTRIBUTE || customAttributeKey == null) {
            "customAttributeKey should only be set for CUSTOM_ATTRIBUTE type"
        }
    }

    /**
     * String representation for storage/lookup.
     * - EMAIL -> "email"
     * - PHONE -> "phone"
     * - CUSTOM_ATTRIBUTE("discord") -> "custom:discord"
     */
    public val key: String
        get() = when (type) {
            ContactType.EMAIL -> "email"
            ContactType.PHONE -> "phone"
            ContactType.CUSTOM_ATTRIBUTE -> "custom:${customAttributeKey!!}"
        }

    public companion object {
        /**
         * Parse a ContactIdentifier from its string key representation.
         *
         * Examples:
         * - "email" -> ContactIdentifier(EMAIL)
         * - "phone" -> ContactIdentifier(PHONE)
         * - "custom:discord" -> ContactIdentifier(CUSTOM_ATTRIBUTE, "discord")
         */
        public fun fromKey(key: String): ContactIdentifier = when {
            key == "email" -> ContactIdentifier(ContactType.EMAIL)
            key == "phone" -> ContactIdentifier(ContactType.PHONE)
            key.startsWith("custom:") -> {
                val attrKey = key.substringAfter("custom:")
                require(attrKey.isNotEmpty()) { "Custom attribute key cannot be empty" }
                ContactIdentifier(ContactType.CUSTOM_ATTRIBUTE, attrKey)
            }
            else -> error("Invalid contact key: $key")
        }
    }

    override fun toString(): String = key
}
