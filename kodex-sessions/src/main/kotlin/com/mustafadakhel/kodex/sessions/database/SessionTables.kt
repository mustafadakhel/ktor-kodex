package com.mustafadakhel.kodex.sessions.database

import com.mustafadakhel.kodex.sessions.model.SessionStatus
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.math.BigDecimal
import java.util.UUID

public object Sessions : Table("sessions") {
    public val id: Column<UUID> = uuid("id")
    public val realmId: Column<String> = varchar("realm_id", 50).index()
    public val userId: Column<UUID> = uuid("user_id").index()
    public val tokenFamily: Column<UUID> = uuid("token_family").uniqueIndex()
    public val deviceFingerprint: Column<String> = varchar("device_fingerprint", 256).index()
    public val deviceName: Column<String?> = varchar("device_name", 128).nullable()
    public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
    public val userAgent: Column<String?> = text("user_agent").nullable()
    public val location: Column<String?> = varchar("location", 100).nullable()
    public val latitude: Column<BigDecimal?> = decimal("latitude", 10, 7).nullable()
    public val longitude: Column<BigDecimal?> = decimal("longitude", 10, 7).nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at").index()
    public val lastActivityAt: Column<LocalDateTime> = datetime("last_activity_at")
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at").index()
    public val status: Column<SessionStatus> = enumerationByName("status", 20, SessionStatus::class)
    public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()
    public val revokedReason: Column<String?> = varchar("revoked_reason", 255).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}

public object SessionHistory : Table("session_history") {
    public val id: Column<UUID> = uuid("id")
    public val realmId: Column<String> = varchar("realm_id", 50).index()
    public val userId: Column<UUID> = uuid("user_id").index()
    public val sessionId: Column<UUID> = uuid("session_id")
    public val deviceName: Column<String?> = varchar("device_name", 128).nullable()
    public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
    public val location: Column<String?> = varchar("location", 100).nullable()
    public val loginAt: Column<LocalDateTime> = datetime("login_at").index()
    public val logoutAt: Column<LocalDateTime?> = datetime("logout_at").nullable()
    public val endReason: Column<String> = varchar("end_reason", 50)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
