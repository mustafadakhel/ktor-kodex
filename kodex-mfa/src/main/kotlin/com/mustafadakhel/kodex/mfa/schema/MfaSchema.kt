package com.mustafadakhel.kodex.mfa.schema

import com.mustafadakhel.kodex.mfa.MfaMethodType
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

public class MfaSchema(private val core: CoreSchema) : ExtensionSchema {

    public val mfaMethods: MfaMethodsTable = MfaMethodsTable(core)
    public val mfaChallenges: MfaChallengesTable = MfaChallengesTable(core)
    public val mfaBackupCodes: MfaBackupCodesTable = MfaBackupCodesTable(core)
    public val mfaTotpUsedCodes: MfaTotpUsedCodesTable = MfaTotpUsedCodesTable(core)
    public val mfaTrustedDevices: MfaTrustedDevicesTable = MfaTrustedDevicesTable(core)

    public class MfaMethodsTable(core: CoreSchema) : Table("${core.prefix}mfa_methods") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val methodType: Column<MfaMethodType> = enumeration("method_type", MfaMethodType::class)
        public val identifier: Column<String?> = varchar("identifier", 255).nullable()
        public val encryptedSecret: Column<String?> = text("encrypted_secret").nullable()
        public val encryptionNonce: Column<String?> = varchar("encryption_nonce", 32).nullable()
        public val isActive: Column<Boolean> = bool("is_active").default(true)
        public val isPrimary: Column<Boolean> = bool("is_primary").default(false)
        public val enrolledAt: Column<LocalDateTime> = datetime("enrolled_at").defaultExpression(CurrentDateTime)
        public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            uniqueIndex(realmId, userId, methodType, identifier)
            index(false, realmId, userId, isActive)
            index(false, realmId, userId, isPrimary)
            index(false, realmId)
        }
    }

    public class MfaChallengesTable(core: CoreSchema) : Table("${core.prefix}mfa_challenges") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val methodId: Column<UUID> = uuid("method_id")
        public val codeHash: Column<String> = varchar("code_hash", 255)
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)
        public val attempts: Column<Int> = integer("attempts").default(0)
        public val maxAttempts: Column<Int> = integer("max_attempts").default(5)
        public val verifiedAt: Column<LocalDateTime?> = datetime("verified_at").nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            index(false, realmId)
            index(false, userId)
            index(false, methodId)
            index(false, realmId, expiresAt)
            index(false, verifiedAt)
        }
    }

    public class MfaBackupCodesTable(core: CoreSchema) : Table("${core.prefix}mfa_backup_codes") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val codeHash: Column<String> = varchar("code_hash", 255)
        public val usedAt: Column<LocalDateTime?> = datetime("used_at").nullable()
        public val createdAt: Column<LocalDateTime> = datetime("created_at").defaultExpression(CurrentDateTime)

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            index(false, realmId)
            index(false, userId)
            index(false, realmId, userId, usedAt)
        }
    }

    public class MfaTotpUsedCodesTable(core: CoreSchema) : Table("${core.prefix}mfa_totp_used_codes") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val methodId: Column<UUID> = uuid("method_id")
        public val codeHash: Column<String> = varchar("code_hash", 255)
        public val usedAt: Column<LocalDateTime> = datetime("used_at").defaultExpression(CurrentDateTime)

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            index(false, realmId)
            index(false, userId, methodId)
            index(false, realmId, usedAt)
            uniqueIndex(realmId, userId, methodId, codeHash)
        }
    }

    public class MfaTrustedDevicesTable(core: CoreSchema) : Table("${core.prefix}mfa_trusted_devices") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<EntityID<UUID>> = reference("user_id", core.users, onDelete = CASCADE)
        public val deviceFingerprint: Column<String> = varchar("device_fingerprint", 256)
        public val deviceName: Column<String?> = varchar("device_name", 128).nullable()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
        public val userAgent: Column<String?> = text("user_agent").nullable()
        public val trustedAt: Column<LocalDateTime> = datetime("trusted_at")
        public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()
        public val expiresAt: Column<LocalDateTime?> = datetime("expires_at").nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            uniqueIndex(realmId, userId, deviceFingerprint)
            index(false, realmId)
            index(false, userId)
            index(false, deviceFingerprint)
            index(false, realmId, expiresAt)
        }
    }

    override fun tables(): List<Table> = listOf(
        mfaMethods,
        mfaChallenges,
        mfaBackupCodes,
        mfaTotpUsedCodes,
        mfaTrustedDevices
    )
}
