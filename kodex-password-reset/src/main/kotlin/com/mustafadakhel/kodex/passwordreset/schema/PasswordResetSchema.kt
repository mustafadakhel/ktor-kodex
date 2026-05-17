package com.mustafadakhel.kodex.passwordreset.schema

import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.CoreTable
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.ReferenceAction
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.LocalDateTime
import java.util.UUID

public class PasswordResetSchema(private val prefix: String) : ExtensionSchema {

    public val passwordResetContacts: PasswordResetContactsTable = PasswordResetContactsTable(prefix)
    public val passwordResetTokens: PasswordResetTokensTable = PasswordResetTokensTable(prefix)

    public class PasswordResetContactsTable(prefix: String) : TableDef("${prefix}password_reset_contacts", prefix) {
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val contactType: Column<String> = varchar("contact_type", 50)
        public val contactValue: Column<String> = varchar("contact_value", 255)
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")
        public val updatedAt: Column<LocalDateTime> = datetime("updated_at").default("CURRENT_TIMESTAMP")

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(realmId, userId, contactType)

        init {
            index(realmId)
            index(contactValue)
        }
    }

    public class PasswordResetTokensTable(prefix: String) : TableDef("${prefix}password_reset_tokens", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val token: Column<String> = varchar("token", 64)
        public val contactValue: Column<String> = varchar("contact_value", 255)
        public val createdAt: Column<LocalDateTime> = datetime("created_at")
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val usedAt: Column<LocalDateTime?> = datetime("used_at").nullable()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            index(realmId)
            index(realmId, expiresAt)
            index(createdAt)
            index(usedAt)
            index(realmId, token)
        }
    }

    override fun tables(): List<TableDef> = listOf(passwordResetContacts, passwordResetTokens)
}
