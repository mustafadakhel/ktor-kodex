package com.mustafadakhel.kodex.sessions.schema

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.sessions.model.SessionStatus
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.math.BigDecimal
import java.util.UUID

public class SessionSchema(private val core: CoreSchema) : ExtensionSchema {

    public val sessions: SessionsTable = SessionsTable(core)
    public val sessionHistory: SessionHistoryTable = SessionHistoryTable(core)

    public class SessionsTable(core: CoreSchema) : Table("${core.prefix}sessions") {
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
        public val status: Column<SessionStatus> = enumerationByName<SessionStatus>("status", 20)
        public val revokedAt: Column<LocalDateTime?> = datetime("revoked_at").nullable()
        public val revokedReason: Column<String?> = varchar("revoked_reason", 255).nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    public class SessionHistoryTable(core: CoreSchema) : Table("${core.prefix}session_history") {
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

    private val allTables: List<Table> = listOf(sessions, sessionHistory)

    internal fun exposedTables(): List<Table> = allTables

    override fun ddl(dialect: DatabaseDialect): List<String> =
        SchemaUtils.createStatements(*allTables.toTypedArray())

    override fun tableNames(): List<String> =
        allTables.map { it.tableName }
}
