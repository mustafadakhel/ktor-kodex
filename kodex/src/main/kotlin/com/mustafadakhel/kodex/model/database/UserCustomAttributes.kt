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

        fun createForUser(userId: UUID, customAttributes: Map<String, String>) {
            customAttributes.forEach { (attrKey, attrValue) ->
                new {
                    this.userId = EntityID(userId, Users)
                    this.key = attrKey
                    this.value = attrValue
                }
            }
        }

        fun replaceAllForUser(userId: UUID, customAttributes: Map<String, String>) {
            find { UserCustomAttributes.userId eq userId }
                .forEach { it.delete() }
            createForUser(userId, customAttributes)
        }

        fun updateForUser(userId: UUID, customAttributes: Map<String, String>) {
            customAttributes.forEach { (attrKey, attrValue) ->
                val existing = find {
                    (UserCustomAttributes.userId eq userId) and
                            (UserCustomAttributes.key eq attrKey)
                }.firstOrNull()

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