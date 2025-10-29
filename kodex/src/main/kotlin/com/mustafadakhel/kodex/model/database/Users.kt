package com.mustafadakhel.kodex.model.database

import com.mustafadakhel.kodex.model.UserStatus
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.*

internal object Users : UUIDTable() {
    val passwordHash = varchar("password_hash", 255)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    val isVerified = bool("is_verified").default(false)
    val phoneNumber = varchar("phone_number", 20).nullable().uniqueIndex()
    val email = varchar("email", 255).nullable().uniqueIndex()
    val lastLoginAt = datetime("last_login_at").nullable()
    val status = enumeration("status", UserStatus::class).default(UserStatus.ACTIVE)
}

internal class UserDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserDao>(Users)

    var passwordHash by Users.passwordHash
    var createdAt by Users.createdAt
    var updatedAt by Users.updatedAt
    var isVerified by Users.isVerified
    var phoneNumber by Users.phoneNumber
    var email by Users.email
    var lastLoginAt by Users.lastLoginAt
    var status by Users.status

    var roles by RoleDao via UserRoles
    val profile by UserProfileDao optionalBackReferencedOn UserProfiles
    val customAttributes by UserCustomAttributesDao referrersOn UserCustomAttributes.userId

    fun allCustomAttributes() = customAttributes.associate { it.key to it.value }
    fun allRoles() = roles.toList()
}
