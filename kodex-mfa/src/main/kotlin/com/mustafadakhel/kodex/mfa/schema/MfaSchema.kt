package com.mustafadakhel.kodex.mfa.schema

import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.CoreTable
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.ReferenceAction
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.mfa.MfaMethodType
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.LocalDateTime
import java.util.UUID

public class MfaSchema(private val prefix: String) : ExtensionSchema {

    public val mfaMethods: MfaMethodsTable = MfaMethodsTable(prefix)
    public val mfaChallenges: MfaChallengesTable = MfaChallengesTable(prefix)
    public val mfaBackupCodes: MfaBackupCodesTable = MfaBackupCodesTable(prefix)
    public val mfaTotpUsedCodes: MfaTotpUsedCodesTable = MfaTotpUsedCodesTable(prefix)
    public val mfaTrustedDevices: MfaTrustedDevicesTable = MfaTrustedDevicesTable(prefix)

    public class MfaMethodsTable(prefix: String) : TableDef("${prefix}mfa_methods", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val methodType: Column<MfaMethodType> = enumByName<MfaMethodType>("method_type", 10)
        public val identifier: Column<String?> = varchar("identifier", 255).nullable()
        public val encryptedSecret: Column<String?> = text("encrypted_secret").nullable()
        public val encryptionNonce: Column<String?> = varchar("encryption_nonce", 32).nullable()
        public val isActive: Column<Boolean> = bool("is_active").default("TRUE")
        public val isPrimary: Column<Boolean> = bool("is_primary").default("FALSE")
        public val enrolledAt: Column<LocalDateTime> = datetime("enrolled_at").default("CURRENT_TIMESTAMP")
        public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(realmId, userId, methodType, identifier)
            index(realmId, userId, isActive)
            index(realmId, userId, isPrimary)
            index(realmId)
        }
    }

    public class MfaChallengesTable(prefix: String) : TableDef("${prefix}mfa_challenges", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val methodId: Column<UUID> = uuid("method_id")
        public val codeHash: Column<String> = varchar("code_hash", 255)
        public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")
        public val attempts: Column<Int> = integer("attempts").default("0")
        public val maxAttempts: Column<Int> = integer("max_attempts").default("5")
        public val verifiedAt: Column<LocalDateTime?> = datetime("verified_at").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            index(realmId)
            index(methodId)
            index(realmId, expiresAt)
            index(verifiedAt)
        }
    }

    public class MfaBackupCodesTable(prefix: String) : TableDef("${prefix}mfa_backup_codes", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val codeHash: Column<String> = varchar("code_hash", 255)
        public val usedAt: Column<LocalDateTime?> = datetime("used_at").nullable()
        public val createdAt: Column<LocalDateTime> = datetime("created_at").default("CURRENT_TIMESTAMP")

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            index(realmId)
            index(realmId, userId, usedAt)
        }
    }

    public class MfaTotpUsedCodesTable(prefix: String) : TableDef("${prefix}mfa_totp_used_codes", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val methodId: Column<UUID> = uuid("method_id")
        public val codeHash: Column<String> = varchar("code_hash", 255)
        public val usedAt: Column<LocalDateTime> = datetime("used_at").default("CURRENT_TIMESTAMP")

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            index(realmId)
            index(userId, methodId)
            index(realmId, usedAt)
            uniqueIndex(realmId, userId, methodId, codeHash)
        }
    }

    public class MfaTrustedDevicesTable(prefix: String) : TableDef("${prefix}mfa_trusted_devices", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val deviceFingerprint: Column<String> = varchar("device_fingerprint", 256)
        public val deviceName: Column<String?> = varchar("device_name", 128).nullable()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
        public val userAgent: Column<String?> = text("user_agent").nullable()
        public val trustedAt: Column<LocalDateTime> = datetime("trusted_at")
        public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()
        public val expiresAt: Column<LocalDateTime?> = datetime("expires_at").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(realmId, userId, deviceFingerprint)
            index(realmId)
            index(deviceFingerprint)
            index(realmId, expiresAt)
        }
    }

    override fun tables(): List<TableDef> = listOf(
        mfaMethods,
        mfaChallenges,
        mfaBackupCodes,
        mfaTotpUsedCodes,
        mfaTrustedDevices,
    )
}
