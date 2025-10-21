package com.mustafadakhel.kodex.model.database

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and
import java.util.*

internal object UserCustomAttributes : IntIdTable() {
    val userId = reference("user_id", Users)
    val key = varchar("key", 255)
    val value = varchar("value", 255)

    init {
        uniqueIndex(userId, key)
    }
}

internal class UserCustomAttributesDao(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<UserCustomAttributesDao>(UserCustomAttributes) {
        private const val MAX_ATTRIBUTES_PER_USER = 100
        private const val MAX_KEY_LENGTH = 255
        private const val MAX_VALUE_LENGTH = 255

        fun createForUser(userId: UUID, customAttributes: Map<String, String>) {
            require(customAttributes.size <= MAX_ATTRIBUTES_PER_USER) {
                "Too many custom attributes (max: $MAX_ATTRIBUTES_PER_USER, attempted: ${customAttributes.size})"
            }
            customAttributes.forEach { (key, value) ->
                require(key.length <= MAX_KEY_LENGTH) {
                    "Custom attribute key too long (max: $MAX_KEY_LENGTH, key: ${key.take(50)}...)"
                }
                require(value.length <= MAX_VALUE_LENGTH) {
                    "Custom attribute value too long (max: $MAX_VALUE_LENGTH, key: $key)"
                }
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
                require(key.length <= MAX_KEY_LENGTH) {
                    "Custom attribute key too long (max: $MAX_KEY_LENGTH, key: ${key.take(50)}...)"
                }
                require(value.length <= MAX_VALUE_LENGTH) {
                    "Custom attribute value too long (max: $MAX_VALUE_LENGTH, key: $key)"
                }
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
                require(key.length <= MAX_KEY_LENGTH) {
                    "Custom attribute key too long (max: $MAX_KEY_LENGTH, key: ${key.take(50)}...)"
                }
                require(value.length <= MAX_VALUE_LENGTH) {
                    "Custom attribute value too long (max: $MAX_VALUE_LENGTH, key: $key)"
                }
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