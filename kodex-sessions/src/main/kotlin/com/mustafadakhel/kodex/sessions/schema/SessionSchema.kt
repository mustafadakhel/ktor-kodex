package com.mustafadakhel.kodex.sessions.schema

import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.CoreTable
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.ReferenceAction
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal
import java.util.UUID

public class SessionSchema(private val prefix: String) : ExtensionSchema {

    public val sessions: SessionsTable = SessionsTable(prefix)
    public val sessionHistory: SessionHistoryTable = SessionHistoryTable(prefix)

    public class SessionsTable(prefix: String) : TableDef("${prefix}sessions", prefix) {
        public val id: Column<UUID> = uuid("id")
        public val realmId: Column<String> = varchar("realm_id", 50).index()
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val tokenFamily: Column<UUID> = uuid("token_family")
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
        public val status: Column<SessionStatus> = enumByName<SessionStatus>("status", 20)
        public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()
        public val revokedReason: Column<String?> = varchar("revoked_reason", 255).nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(tokenFamily)
        }
    }

    public class SessionHistoryTable(prefix: String) : TableDef("${prefix}session_history", prefix) {
        public val id: Column<UUID> = uuid("id")
        public val realmId: Column<String> = varchar("realm_id", 50).index()
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val sessionId: Column<UUID> = uuid("session_id")
        public val deviceName: Column<String?> = varchar("device_name", 128).nullable()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
        public val location: Column<String?> = varchar("location", 100).nullable()
        public val loginAt: Column<LocalDateTime> = datetime("login_at").index()
        public val logoutAt: Column<LocalDateTime?> = datetime("logout_at").nullable()
        public val endReason: Column<String> = varchar("end_reason", 50)

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)
    }

    override fun tables(): List<TableDef> = listOf(sessions, sessionHistory)
}
