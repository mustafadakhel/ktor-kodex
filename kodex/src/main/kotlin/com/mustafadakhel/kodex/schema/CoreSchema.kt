package com.mustafadakhel.kodex.schema

import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.ReferenceAction
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.model.UserStatus
import kotlinx.datetime.LocalDateTime
import java.util.UUID

public class CoreSchema(public val prefix: String = "kodex_") {

    public val users: UsersTable = UsersTable(prefix)
    public val roles: RolesTable = RolesTable(prefix)
    public val userRoles: UserRolesTable = UserRolesTable(prefix, users, roles)
    public val tokens: TokensTable = TokensTable(prefix, users)
    public val userProfiles: UserProfilesTable = UserProfilesTable(prefix, users)
    public val userCustomAttributes: UserCustomAttributesTable = UserCustomAttributesTable(prefix, users)

    public class UsersTable(prefix: String) : TableDef("${prefix}users", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val passwordHash: Column<String> = varchar("password_hash", 255)
        public val email: Column<String?> = varchar("email", 255).nullable()
        public val phoneNumber: Column<String?> = varchar("phone_number", 20).nullable()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val status: Column<UserStatus> = enumByName<UserStatus>("status", 50).default("'ACTIVE'")
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")
        public val updatedAt: Column<LocalDateTime> = datetime("updated_at").default("CURRENT_TIMESTAMP")
        public val lastLoginAt: Column<LocalDateTime?> = datetime("last_login_at").nullable()

        public val emailRealmIndex: String = "${prefix}$IDX_USERS_EMAIL_REALM"
        public val phoneRealmIndex: String = "${prefix}$IDX_USERS_PHONE_REALM"

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(email, realmId, name = emailRealmIndex)
            uniqueIndex(phoneNumber, realmId, name = phoneRealmIndex)
            index(realmId)
        }

        internal companion object {
            const val IDX_USERS_EMAIL_REALM = "idx_users_email_realm"
            const val IDX_USERS_PHONE_REALM = "idx_users_phone_realm"
        }
    }

    public class RolesTable(prefix: String) : TableDef("${prefix}roles", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val name: Column<String> = varchar("name", 50)
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val description: Column<String?> = text("description").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(name, realmId)
        }
    }

    public class UserRolesTable(
        prefix: String,
        users: UsersTable,
        roles: RolesTable,
    ) : TableDef("${prefix}user_roles", prefix) {
        public val userId: Column<UUID> = uuid("user_id").references(users.id, ReferenceAction.CASCADE)
        public val roleId: Column<UUID> = uuid("role_id").references(roles.id, ReferenceAction.CASCADE)

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(userId, roleId)
    }

    public class TokensTable(prefix: String, users: UsersTable) : TableDef("${prefix}tokens", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val userId: Column<UUID> = uuid("user_id").references(users.id, ReferenceAction.CASCADE).index()
        public val tokenHash: Column<String> = text("token_hash").index()
        public val type: Column<String> = varchar("type", 16)
        public val revoked: Column<Boolean> = bool("revoked").default("FALSE")
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val tokenFamily: Column<UUID?> = uuid("token_family").nullable().index()
        public val parentTokenId: Column<UUID?> = uuid("parent_token_id").nullable()
        public val firstUsedAt: Column<LocalDateTime?> = datetime("first_used_at").nullable()
        public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()
        public val realmId: Column<String> = varchar("realm_id", 50).index()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)
    }

    public class UserProfilesTable(prefix: String, users: UsersTable) : TableDef("${prefix}user_profiles", prefix) {
        public val userId: Column<UUID> = uuid("user_id").references(users.id, ReferenceAction.CASCADE)
        public val firstName: Column<String?> = varchar("first_name", 255).nullable()
        public val lastName: Column<String?> = varchar("last_name", 255).nullable()
        public val address: Column<String?> = text("address").nullable()
        public val profilePicture: Column<String?> = text("profile_picture").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(userId)
    }

    public class UserCustomAttributesTable(
        prefix: String,
        users: UsersTable,
    ) : TableDef("${prefix}user_custom_attributes", prefix) {
        public val id: Column<Int> = integer("id").autoGenerate()
        public val userId: Column<UUID> = uuid("user_id").references(users.id, ReferenceAction.CASCADE)
        public val key: Column<String> = varchar("attr_key", 100)
        public val value: Column<String> = text("attr_value")

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(userId, key)
        }
    }

    public fun tables(): List<TableDef> = listOf(users, roles, userRoles, tokens, userProfiles, userCustomAttributes)
}
