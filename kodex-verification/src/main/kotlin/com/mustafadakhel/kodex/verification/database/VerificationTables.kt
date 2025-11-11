package com.mustafadakhel.kodex.verification.database

import com.mustafadakhel.kodex.verification.ContactType
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

/**
 * Table for storing verifiable contacts (email, phone, custom attributes).
 * Tracks verification status per contact type.
 */
internal object VerifiableContacts : UUIDTable("verifiable_contacts") {
    public val realmId: Column<String> = varchar("realm_id", 50)
    public val userId: Column<UUID> = uuid("user_id")
    public val contactType: Column<ContactType> = enumeration("contact_type", ContactType::class)
    public val customAttributeKey: Column<String?> = varchar("custom_attribute_key", 128).nullable()
    public val contactValue: Column<String> = varchar("contact_value", 255)
    public val isVerified: Column<Boolean> = bool("is_verified").default(false)
    public val verifiedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("verified_at").nullable()
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    public val updatedAt: Column<kotlinx.datetime.LocalDateTime> = datetime("updated_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex(realmId, userId, contactType, customAttributeKey)
        index(false, realmId)
        index(false, userId)
    }
}

/**
 * Table for storing verification tokens.
 * Used for email/SMS/custom attribute verification workflows.
 */
internal object VerificationTokens : UUIDTable("verification_tokens") {
    public val realmId: Column<String> = varchar("realm_id", 50)
    public val userId: Column<UUID> = uuid("user_id")
    public val contactType: Column<ContactType> = enumeration("contact_type", ContactType::class)
    public val customAttributeKey: Column<String?> = varchar("custom_attribute_key", 128).nullable()
    public val token: Column<String> = varchar("token", 255).uniqueIndex()
    public val expiresAt: Column<kotlinx.datetime.LocalDateTime> = datetime("expires_at")
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    public val usedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("used_at").nullable()

    init {
        index(false, realmId)
        index(false, userId)
        index(false, realmId, expiresAt)
        index(false, createdAt)
        index(false, usedAt)
    }
}
