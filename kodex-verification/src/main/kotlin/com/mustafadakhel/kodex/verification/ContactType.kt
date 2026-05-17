package com.mustafadakhel.kodex.verification

public sealed interface ContactType {

    /**
     * String key for storage and lookup.
     * - Email -> "email"
     * - Phone -> "phone"
     * - CustomAttribute("discord") -> "custom:discord"
     */
    public val key: String

    public data object Email : ContactType {
        override val key: String = "email"
    }

    public data object Phone : ContactType {
        override val key: String = "phone"
    }

    public data class CustomAttribute(override val key: String) : ContactType {
        init {
            require(key.isNotBlank()) { "Custom attribute key cannot be blank" }
        }
    }

    public companion object {
        /**
         * Parse a ContactType from its string key representation.
         *
         * - "email" -> Email
         * - "phone" -> Phone
         * - "discord" -> CustomAttribute("discord")
         */
        public fun fromKey(key: String): ContactType = when (key) {
            "email" -> Email
            "phone" -> Phone
            else -> {
                require(key.isNotBlank()) { "Contact type key cannot be blank" }
                CustomAttribute(key)
            }
        }
    }
}
