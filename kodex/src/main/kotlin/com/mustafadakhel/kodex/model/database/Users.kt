package com.mustafadakhel.kodex.model.database

import com.mustafadakhel.kodex.model.UserStatus
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.*

internal object Users : UUIDTable() {
    public val passwordHash: Column<String> = varchar("password_hash", 255)
    public val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)
    public val phoneNumber: Column<String?> = varchar("phone_number", 20).nullable()
    public val email: Column<String?> = varchar("email", 255).nullable()
    public val lastLoginAt: Column<LocalDateTime?> = datetime("last_login_at").nullable()
    public val status: Column<UserStatus> = enumeration("status", UserStatus::class).default(UserStatus.ACTIVE)
    public val realmId: Column<String> = varchar("realm_id", 50)

    init {
        uniqueIndex("idx_users_email_realm", email, realmId)
        uniqueIndex("idx_users_phone_realm", phoneNumber, realmId)
    }
}

internal class UserDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UserDao>(Users)

    var passwordHash by Users.passwordHash
    var createdAt by Users.createdAt
    var updatedAt by Users.updatedAt
    var phoneNumber by Users.phoneNumber
    var email by Users.email
    var lastLoginAt by Users.lastLoginAt
    var status by Users.status
    var realmId by Users.realmId

    var roles by RoleDao via UserRoles
    val profile by UserProfileDao optionalBackReferencedOn UserProfiles
    val customAttributes by UserCustomAttributesDao referrersOn UserCustomAttributes.userId

    fun allCustomAttributes() = customAttributes.associate { it.key to it.value }
    fun allRoles() = roles.toList()
}
