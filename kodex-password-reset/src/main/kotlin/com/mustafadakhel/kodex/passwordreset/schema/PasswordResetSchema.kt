package com.mustafadakhel.kodex.passwordreset.schema

import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

public class PasswordResetSchema(private val core: CoreSchema) : ExtensionSchema {

    public val passwordResetContacts: PasswordResetContactsTable = PasswordResetContactsTable(core)
    public val passwordResetTokens: PasswordResetTokensTable = PasswordResetTokensTable(core)

    public class PasswordResetContactsTable(core: CoreSchema) : Table("${core.prefix}password_reset_contacts") {
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val contactType: Column<String> = varchar("contact_type", 50)
        public val contactValue: Column<String> = varchar("contact_value", 255)
        public val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
        public val updatedAt: Column<LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)

        override val primaryKey: PrimaryKey = PrimaryKey(realmId, userId, contactType)

        init {
            index(false, realmId)
            index(false, userId)
            index(false, contactValue)
        }
    }

    public class PasswordResetTokensTable(core: CoreSchema) : Table("${core.prefix}password_reset_tokens") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val token: Column<String> = varchar("token", 64).uniqueIndex()
        public val contactValue: Column<String> = varchar("contact_value", 255)
        public val createdAt: Column<LocalDateTime> = datetime("created_at")
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val usedAt: Column<LocalDateTime?> = datetime("used_at").nullable()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            index(false, realmId)
            index(false, userId)
            index(false, realmId, expiresAt)
            index(false, createdAt)
            index(false, usedAt)
        }
    }

    override fun tables(): List<Table> = listOf(passwordResetContacts, passwordResetTokens)
}
