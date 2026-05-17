package com.mustafadakhel.kodex.verification.schema

import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.CoreTable
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.ReferenceAction
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.LocalDateTime
import java.util.UUID

public class VerificationSchema(private val prefix: String) : ExtensionSchema {

    public val verifiableContacts: VerifiableContactsTable = VerifiableContactsTable(prefix)
    public val verificationTokens: VerificationTokensTable = VerificationTokensTable(prefix)

    public class VerifiableContactsTable(prefix: String) : TableDef("${prefix}verifiable_contacts", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val contactType: Column<String> = varchar("contact_type", 50)
        public val contactValue: Column<String> = varchar("contact_value", 255)
        public val isVerified: Column<Boolean> = bool("is_verified").default("FALSE")
        public val verifiedAt: Column<LocalDateTime?> = datetime("verified_at").nullable()
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")
        public val updatedAt: Column<LocalDateTime> = datetime("updated_at").default("CURRENT_TIMESTAMP")

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(realmId, userId, contactType)
            index(realmId)
        }
    }

    public class VerificationTokensTable(prefix: String) : TableDef("${prefix}verification_tokens", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val contactType: Column<String> = varchar("contact_type", 50)
        public val token: Column<String> = varchar("token", 64)
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")
        public val usedAt: Column<LocalDateTime?> = datetime("used_at").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            index(realmId)
            index(realmId, token)
            index(realmId, expiresAt)
            index(createdAt)
            index(usedAt)
        }
    }

    override fun tables(): List<TableDef> = listOf(verifiableContacts, verificationTokens)
}
