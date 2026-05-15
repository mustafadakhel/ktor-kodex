package com.mustafadakhel.kodex.audit.schema

import com.mustafadakhel.kodex.audit.ActorType
import com.mustafadakhel.kodex.audit.EventResult
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.util.UUID

public class AuditSchema(private val core: CoreSchema) : ExtensionSchema {

    public val auditEvents: AuditEventsTable = AuditEventsTable(core)

    public class AuditEventsTable(core: CoreSchema) : Table("${core.prefix}audit_events") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val eventType: Column<String> = varchar("event_type", 100).index()
        public val timestamp: Column<Instant> = timestamp("timestamp").index()
        public val actorId: Column<UUID?> = uuid("actor_id").nullable().index()
        public val actorType: Column<ActorType> = enumerationByName<ActorType>("actor_type", 20)
        public val targetId: Column<UUID?> = uuid("target_id").nullable().index()
        public val targetType: Column<String?> = varchar("target_type", 100).nullable()
        public val result: Column<EventResult> = enumerationByName<EventResult>("result", 20)
        public val metadata: Column<String> = text("metadata")
        public val realmId: Column<String> = varchar("realm_id", 50).index()
        public val sessionId: Column<UUID?> = uuid("session_id").nullable()

        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    override fun tables(): List<Table> = listOf(auditEvents)
}
