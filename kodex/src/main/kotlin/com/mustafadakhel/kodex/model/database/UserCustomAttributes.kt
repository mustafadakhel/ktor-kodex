package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and
import java.util.*

internal object UserCustomAttributes : IntIdTable() {
    val userId = reference("user_id", Users)
    val key = varchar("key", 100)
    val value = text("value")

    init {
        uniqueIndex(userId, key)
    }
}

internal class UserCustomAttributesDao(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserCustomAttributesDao>(UserCustomAttributes) {
        private const val MAX_ATTRIBUTES_PER_USER = 100
        private const val MAX_KEY_LENGTH = 100
        private const val MAX_VALUE_LENGTH = 4096

        /**
         * Valid key format: alphanumeric, underscore, hyphen only.
         * Prevents SQL injection and special character attacks.
         */
        private val VALID_KEY_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

        /**
         * Blocked keys that could enable prototype pollution or other attacks.
         */
        private val BLOCKED_KEYS = setOf(
            "__proto__",
            "constructor",
            "prototype",
            "__defineGetter__",
            "__defineSetter__",
            "__lookupGetter__",
            "__lookupSetter__"
        )

        /**
         * Validates a custom attribute key for security.
         *
         * @throws IllegalArgumentException if key is invalid
         */
        private fun validateKey(key: String) {
            require(key.isNotBlank()) {
                "Custom attribute key cannot be blank"
            }
            require(key.length <= MAX_KEY_LENGTH) {
                "Custom attribute key too long (max: $MAX_KEY_LENGTH, actual: ${key.length})"
            }
            require(key.matches(VALID_KEY_PATTERN)) {
                "Custom attribute key contains invalid characters (allowed: a-zA-Z0-9_-): $key"
            }
            require(key.lowercase() !in BLOCKED_KEYS.map { it.lowercase() }) {
                "Custom attribute key is blocked for security reasons: $key"
            }
        }

        /**
         * Validates a custom attribute value.
         *
         * @throws IllegalArgumentException if value is invalid
         */
        private fun validateValue(key: String, value: String) {
            require(value.length <= MAX_VALUE_LENGTH) {
                "Custom attribute value too long (max: $MAX_VALUE_LENGTH, key: $key, actual: ${value.length})"
            }
        }

        fun createForUser(userId: UUID, customAttributes: Map<String, String>) {
            require(customAttributes.size <= MAX_ATTRIBUTES_PER_USER) {
                "Too many custom attributes (max: $MAX_ATTRIBUTES_PER_USER, attempted: ${customAttributes.size})"
            }
            customAttributes.forEach { (key, value) ->
                validateKey(key)
                validateValue(key, value)
            }
            customAttributes.forEach { (attrKey, attrValue) ->
                new {
                    this.userId = EntityID(userId, Users)
                    this.key = attrKey
                    this.value = attrValue
                }
            }
        }

        fun replaceAllForUser(userId: UUID, customAttributes: Map<String, String>) {
            require(customAttributes.size <= MAX_ATTRIBUTES_PER_USER) {
                "Too many custom attributes (max: $MAX_ATTRIBUTES_PER_USER, attempted: ${customAttributes.size})"
            }
            customAttributes.forEach { (key, value) ->
                validateKey(key)
                validateValue(key, value)
            }
            find { UserCustomAttributes.userId eq userId }
                .forEach { it.delete() }
            customAttributes.forEach { (attrKey, attrValue) ->
                new {
                    this.userId = EntityID(userId, Users)
                    this.key = attrKey
                    this.value = attrValue
                }
            }
        }

        fun updateForUser(userId: UUID, customAttributes: Map<String, String>) {
            customAttributes.forEach { (key, value) ->
                validateKey(key)
                validateValue(key, value)
            }

            val existingAttrs = find { UserCustomAttributes.userId eq userId }
                .associateBy { it.key }

            val newKeysCount = customAttributes.keys.count { it !in existingAttrs }
            val totalAfterUpdate = existingAttrs.size + newKeysCount

            require(totalAfterUpdate <= MAX_ATTRIBUTES_PER_USER) {
                "Too many custom attributes after update (max: $MAX_ATTRIBUTES_PER_USER, would be: $totalAfterUpdate)"
            }

            customAttributes.forEach { (attrKey, attrValue) ->
                val existing = existingAttrs[attrKey]

                if (existing != null) {
                    existing.value = attrValue
                } else {
                    new {
                        this.userId = EntityID(userId, Users)
                        this.key = attrKey
                        this.value = attrValue
                    }
                }
            }
        }

        fun findByUserId(userId: UUID): List<UserCustomAttributesDao> =
            find { UserCustomAttributes.userId eq userId }.toList()
    }

    var key by UserCustomAttributes.key
    var value by UserCustomAttributes.value
    var userId by UserCustomAttributes.userId
}