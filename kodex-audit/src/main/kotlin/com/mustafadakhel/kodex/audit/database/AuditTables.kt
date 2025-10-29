package com.mustafadakhel.kodex.audit.database

import com.mustafadakhel.kodex.audit.ActorType
import com.mustafadakhel.kodex.audit.EventResult
import com.mustafadakhel.kodex.audit.MetadataSanitizer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.util.*

/**
 * Database table for audit events with append-only, immutable design.
 *
 * Schema considerations:
 * - Append-only: Events are never updated or deleted (except for retention cleanup)
 * - Indexed: timestamp, eventType, actorId for efficient queries
 * - JSONB metadata: Flexible storage for event-specific data
 * - Supports both PostgreSQL (JSONB) and H2/other databases (JSON)
 */
public object AuditLogs : UUIDTable("audit_events") {

    // Event identification
    public val eventType: org.jetbrains.exposed.sql.Column<String> = varchar("event_type", 100).index()
    public val timestamp: org.jetbrains.exposed.sql.Column<kotlinx.datetime.Instant> = timestamp("timestamp").index()

    // Actor (who performed the action)
    public val actorId: org.jetbrains.exposed.sql.Column<UUID?> = uuid("actor_id").nullable().index()
    public val actorType: org.jetbrains.exposed.sql.Column<ActorType> = enumerationByName<ActorType>("actor_type", 20)

    // Target (what was acted upon)
    public val targetId: org.jetbrains.exposed.sql.Column<UUID?> = uuid("target_id").nullable().index()
    public val targetType: org.jetbrains.exposed.sql.Column<String?> = varchar("target_type", 100).nullable()

    // Result
    public val result: org.jetbrains.exposed.sql.Column<EventResult> = enumerationByName<EventResult>("result", 20)

    // Flexible metadata stored as JSON
    // Note: For PostgreSQL, this will use JSONB. For H2/SQLite, it uses JSON or TEXT.
    public val metadata: org.jetbrains.exposed.sql.Column<String> = text("metadata")

    // Context
    public val realmId: org.jetbrains.exposed.sql.Column<String> = varchar("realm_id", 50).index()
    public val sessionId: org.jetbrains.exposed.sql.Column<UUID?> = uuid("session_id").nullable()
}

/**
 * DAO entity for audit log entries.
 *
 * Provides object-relational mapping for audit events.
 */
public class AuditLogDao(id: EntityID<UUID>) : UUIDEntity(id) {
    public companion object : UUIDEntityClass<AuditLogDao>(AuditLogs) {
        private val json = Json { ignoreUnknownKeys = true }
    }

    public var eventType: String by AuditLogs.eventType
    public var timestamp: kotlinx.datetime.Instant by AuditLogs.timestamp
    public var actorId: UUID? by AuditLogs.actorId
    public var actorType: ActorType by AuditLogs.actorType
    public var targetId: UUID? by AuditLogs.targetId
    public var targetType: String? by AuditLogs.targetType
    public var result: EventResult by AuditLogs.result
    private var metadataJson: String by AuditLogs.metadata
    public var realmId: String by AuditLogs.realmId
    public var sessionId: UUID? by AuditLogs.sessionId

    /**
     * Metadata stored as JSON string with automatic sanitization.
     *
     * Sanitization happens on WRITE to prevent malicious data storage.
     * Data is stored already-sanitized in the database.
     *
     * All metadata values are:
     * - HTML-escaped to prevent XSS attacks
     * - Redacted if they contain sensitive field names (password, token, etc.)
     */
    public var metadata: Map<String, Any>
        get() = if (metadataJson.isBlank()) emptyMap() else json.decodeFromString<Map<String, String>>(metadataJson)
        set(value) {
            val sanitized = MetadataSanitizer.sanitize(value)
            val stringified = sanitized.mapValues { (_, v) -> v.toString() }
            metadataJson = json.encodeToString(stringified)
        }
}
