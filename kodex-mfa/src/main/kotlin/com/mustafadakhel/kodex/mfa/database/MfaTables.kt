package com.mustafadakhel.kodex.mfa.database

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

public enum class MfaMethodType {
    EMAIL,
    TOTP
}

internal object MfaMethods : UUIDTable("mfa_methods") {
    public val realmId: Column<String> = varchar("realm_id", 50)
    public val userId: Column<UUID> = uuid("user_id")
    public val methodType: Column<MfaMethodType> = enumeration("method_type", MfaMethodType::class)
    public val identifier: Column<String?> = varchar("identifier", 255).nullable()
    public val encryptedSecret: Column<String?> = text("encrypted_secret").nullable()
    public val encryptionNonce: Column<String?> = varchar("encryption_nonce", 32).nullable()
    public val isActive: Column<Boolean> = bool("is_active").default(true)
    public val isPrimary: Column<Boolean> = bool("is_primary").default(false)
    public val enrolledAt: Column<kotlinx.datetime.LocalDateTime> = datetime("enrolled_at").defaultExpression(CurrentDateTime)
    public val lastUsedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("last_used_at").nullable()

    init {
        uniqueIndex(realmId, userId, methodType, identifier)
        index(false, realmId, userId, isActive)
        index(false, realmId, userId, isPrimary)
        index(false, realmId)
    }
}

internal object MfaChallenges : UUIDTable("mfa_challenges") {
    public val realmId: Column<String> = varchar("realm_id", 50)
    public val userId: Column<UUID> = uuid("user_id")
    public val methodId: Column<UUID> = uuid("method_id")
    public val codeHash: Column<String> = varchar("code_hash", 255)
    public val expiresAt: Column<kotlinx.datetime.LocalDateTime> = datetime("expires_at")
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    public val attempts: Column<Int> = integer("attempts").default(0)
    public val maxAttempts: Column<Int> = integer("max_attempts").default(5)
    public val verifiedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("verified_at").nullable()

    init {
        index(false, realmId)
        index(false, userId)
        index(false, methodId)
        index(false, realmId, expiresAt)
        index(false, verifiedAt)
    }
}

internal object MfaBackupCodes : UUIDTable("mfa_backup_codes") {
    public val realmId: Column<String> = varchar("realm_id", 50)
    public val userId: Column<UUID> = uuid("user_id")
    public val codeHash: Column<String> = varchar("code_hash", 255)
    public val usedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("used_at").nullable()
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)

    init {
        index(false, realmId)
        index(false, userId)
        index(false, realmId, userId, usedAt)
    }
}
