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

public object MfaMethods : UUIDTable("mfa_methods") {
    public val userId: Column<UUID> = uuid("user_id").index()
    public val methodType: Column<MfaMethodType> = enumeration("method_type", MfaMethodType::class)
    public val identifier: Column<String?> = varchar("identifier", 255).nullable()
    public val encryptedSecret: Column<String?> = text("encrypted_secret").nullable()
    public val encryptionNonce: Column<String?> = varchar("encryption_nonce", 32).nullable()
    public val isActive: Column<Boolean> = bool("is_active").default(true)
    public val isPrimary: Column<Boolean> = bool("is_primary").default(false)
    public val enrolledAt: Column<kotlinx.datetime.LocalDateTime> = datetime("enrolled_at").defaultExpression(CurrentDateTime)
    public val lastUsedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("last_used_at").nullable()

    init {
        uniqueIndex(userId, methodType, identifier)
        index(false, userId, isActive)
        index(false, userId, isPrimary)
    }
}

public object MfaChallenges : UUIDTable("mfa_challenges") {
    public val userId: Column<UUID> = uuid("user_id").index()
    public val methodId: Column<UUID> = uuid("method_id").index()
    public val codeHash: Column<String> = varchar("code_hash", 255)
    public val expiresAt: Column<kotlinx.datetime.LocalDateTime> = datetime("expires_at").index()
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
    public val attempts: Column<Int> = integer("attempts").default(0)
    public val maxAttempts: Column<Int> = integer("max_attempts").default(5)
    public val verifiedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("verified_at").nullable().index()
}

public object MfaBackupCodes : UUIDTable("mfa_backup_codes") {
    public val userId: Column<UUID> = uuid("user_id").index()
    public val codeHash: Column<String> = varchar("code_hash", 255)
    public val usedAt: Column<kotlinx.datetime.LocalDateTime?> = datetime("used_at").nullable()
    public val createdAt: Column<kotlinx.datetime.LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)

    init {
        index(false, userId, usedAt)
    }
}
