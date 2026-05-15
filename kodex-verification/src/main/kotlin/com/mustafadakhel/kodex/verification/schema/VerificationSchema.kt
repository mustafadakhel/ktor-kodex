package com.mustafadakhel.kodex.verification.schema

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

public class VerificationSchema(private val core: CoreSchema) : ExtensionSchema {

    public val verifiableContacts: VerifiableContactsTable = VerifiableContactsTable(core)
    public val verificationTokens: VerificationTokensTable = VerificationTokensTable(core)

    public class VerifiableContactsTable(core: CoreSchema) : Table("${core.prefix}verifiable_contacts") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val contactType: Column<String> = varchar("contact_type", 50)
        public val contactValue: Column<String> = varchar("contact_value", 255)
        public val isVerified: Column<Boolean> = bool("is_verified").default(false)
        public val verifiedAt: Column<LocalDateTime?> = datetime("verified_at").nullable()
        public val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
        public val updatedAt: Column<LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            uniqueIndex(realmId, userId, contactType)
            index(false, realmId)
            index(false, userId)
        }
    }

    public class VerificationTokensTable(core: CoreSchema) : Table("${core.prefix}verification_tokens") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val contactType: Column<String> = varchar("contact_type", 50)
        public val token: Column<String> = varchar("token", 64)
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
        public val usedAt: Column<LocalDateTime?> = datetime("used_at").nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            index(false, realmId)
            index(false, userId)
            index(false, realmId, token)
            index(false, realmId, expiresAt)
            index(false, createdAt)
            index(false, usedAt)
        }
    }

    override fun tables(): List<Table> = listOf(verifiableContacts, verificationTokens)
}
