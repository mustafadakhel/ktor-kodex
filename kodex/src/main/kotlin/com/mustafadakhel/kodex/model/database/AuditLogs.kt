package com.mustafadakhel.kodex.model.database

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
internal object AuditLogs : UUIDTable("audit_events") {

    // Event identification
    val eventType = varchar("event_type", 100).index()
    val timestamp = timestamp("timestamp").index()

    // Actor (who performed the action)
    val actorId = uuid("actor_id").nullable().index()
    val actorType = enumerationByName<ActorType>("actor_type", 20)

    // Target (what was acted upon)
    val targetId = uuid("target_id").nullable().index()
    val targetType = varchar("target_type", 100).nullable()

    // Result
    val result = enumerationByName<EventResult>("result", 20)

    // Flexible metadata stored as JSON
    // Note: For PostgreSQL, this will use JSONB. For H2/SQLite, it uses JSON or TEXT.
    val metadata = text("metadata")

    // Context
    val realmId = varchar("realm_id", 50).index()
    val sessionId = uuid("session_id").nullable()
}

/**
 * DAO entity for audit log entries.
 *
 * Provides object-relational mapping for audit events.
 */
internal class AuditLogDao(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<AuditLogDao>(AuditLogs) {
        private val json = Json { ignoreUnknownKeys = true }
    }

    var eventType by AuditLogs.eventType
    var timestamp by AuditLogs.timestamp
    var actorId by AuditLogs.actorId
    var actorType by AuditLogs.actorType
    var targetId by AuditLogs.targetId
    var targetType by AuditLogs.targetType
    var result by AuditLogs.result
    private var metadataJson by AuditLogs.metadata
    var realmId by AuditLogs.realmId
    var sessionId by AuditLogs.sessionId

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
    var metadata: Map<String, Any>
        get() = if (metadataJson.isBlank()) emptyMap() else json.decodeFromString(metadataJson)
        set(value) {
            val sanitized = MetadataSanitizer.sanitize(value)
            metadataJson = json.encodeToString(sanitized)
        }
}
