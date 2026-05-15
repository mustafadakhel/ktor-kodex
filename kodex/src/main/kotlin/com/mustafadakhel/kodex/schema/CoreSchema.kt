package com.mustafadakhel.kodex.schema

import com.mustafadakhel.kodex.model.UserStatus
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import kotlinx.datetime.LocalDateTime
import java.util.UUID

public class CoreSchema(public val prefix: String = "kodex_") {

    public val users: UsersTable = UsersTable(prefix)
    public val roles: RolesTable = RolesTable(prefix)
    public val userRoles: UserRolesTable = UserRolesTable(prefix, users, roles)
    public val tokens: TokensTable = TokensTable(prefix, users)
    public val userProfiles: UserProfilesTable = UserProfilesTable(prefix, users)
    public val userCustomAttributes: UserCustomAttributesTable = UserCustomAttributesTable(prefix, users)

    public class UsersTable(prefix: String) : UUIDTable("${prefix}users") {
        public val passwordHash: Column<String> = varchar("password_hash", 255)
        public val email: Column<String?> = varchar("email", 255).nullable()
        public val phoneNumber: Column<String?> = varchar("phone_number", 20).nullable()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val status: Column<UserStatus> =
            enumerationByName<UserStatus>("status", 50).default(UserStatus.ACTIVE)
        public val createdAt: Column<LocalDateTime> =
            datetime("created_at").defaultExpression(CurrentDateTime)
        public val updatedAt: Column<LocalDateTime> =
            datetime("updated_at").defaultExpression(CurrentDateTime)
        public val lastLoginAt: Column<LocalDateTime?> =
            datetime("last_login_at").nullable()

        public val emailRealmIndex: String = "${prefix}$IDX_USERS_EMAIL_REALM"
        public val phoneRealmIndex: String = "${prefix}$IDX_USERS_PHONE_REALM"

        init {
            uniqueIndex(emailRealmIndex, email, realmId)
            uniqueIndex(phoneRealmIndex, phoneNumber, realmId)
            index(false, realmId)
        }

        internal companion object {
            const val IDX_USERS_EMAIL_REALM = "idx_users_email_realm"
            const val IDX_USERS_PHONE_REALM = "idx_users_phone_realm"
        }
    }

    public class RolesTable(prefix: String) : UUIDTable("${prefix}roles") {
        public val name: Column<String> = varchar("name", 50)
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val description: Column<String?> = text("description").nullable()

        init {
            uniqueIndex(name, realmId)
        }
    }

    public class UserRolesTable(
        prefix: String,
        users: UsersTable,
        roles: RolesTable,
    ) : Table("${prefix}user_roles") {
        public val userId: Column<EntityID<UUID>> = reference("user_id", users, onDelete = CASCADE)
        public val roleId: Column<EntityID<UUID>> = reference("role_id", roles, onDelete = CASCADE)

        override val primaryKey: PrimaryKey = PrimaryKey(userId, roleId)
    }

    public class TokensTable(prefix: String, users: UsersTable) : UUIDTable("${prefix}tokens") {
        public val userId: Column<EntityID<UUID>> = reference("user_id", users, onDelete = CASCADE).index()
        public val tokenHash: Column<String> = text("token_hash").index()
        public val type: Column<String> = varchar("type", 16)
        public val revoked: Column<Boolean> = bool("revoked").default(false)
        public val createdAt: Column<LocalDateTime> =
            datetime("created_at").defaultExpression(CurrentDateTime)
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val tokenFamily: Column<UUID?> = uuid("token_family").nullable().index()
        public val parentTokenId: Column<UUID?> = uuid("parent_token_id").nullable()
        public val firstUsedAt: Column<LocalDateTime?> = datetime("first_used_at").nullable()
        public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()
        public val realmId: Column<String> = varchar("realm_id", 50).index()
    }

    public class UserProfilesTable(prefix: String, users: UsersTable) : Table("${prefix}user_profiles") {
        public val userId: Column<EntityID<UUID>> = reference("user_id", users, onDelete = CASCADE)
        public val firstName: Column<String?> = varchar("first_name", 255).nullable()
        public val lastName: Column<String?> = varchar("last_name", 255).nullable()
        public val address: Column<String?> = text("address").nullable()
        public val profilePicture: Column<String?> = text("profile_picture").nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(userId)
    }

    public class UserCustomAttributesTable(
        prefix: String,
        users: UsersTable,
    ) : IntIdTable("${prefix}user_custom_attributes") {
        public val userId: Column<EntityID<UUID>> = reference("user_id", users, onDelete = CASCADE)
        public val key: Column<String> = varchar("key", 100)
        public val value: Column<String> = text("value")

        init {
            uniqueIndex(userId, key)
        }
    }

    public fun tables(): List<Table> = listOf(users, roles, userRoles, tokens, userProfiles, userCustomAttributes)
}
