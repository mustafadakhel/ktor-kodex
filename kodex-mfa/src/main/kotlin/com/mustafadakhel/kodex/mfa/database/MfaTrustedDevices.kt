package com.mustafadakhel.kodex.mfa.database

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

internal object MfaTrustedDevices : UUIDTable("mfa_trusted_devices") {
    public val realmId: Column<String> = varchar("realm_id", 50)
    public val userId: Column<UUID> = uuid("user_id")
    public val deviceFingerprint: Column<String> = varchar("device_fingerprint", 256)
    public val deviceName: Column<String?> = varchar("device_name", 128).nullable()
    public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
    public val userAgent: Column<String?> = text("user_agent").nullable()
    public val trustedAt: Column<LocalDateTime> = datetime("trusted_at")
    public val lastUsedAt: Column<LocalDateTime?> = datetime("last_used_at").nullable()
    public val expiresAt: Column<LocalDateTime?> = datetime("expires_at").nullable()

    init {
        uniqueIndex(realmId, userId, deviceFingerprint)
        index(false, realmId)
        index(false, userId)
        index(false, deviceFingerprint)
        index(false, realmId, expiresAt)
    }
}
