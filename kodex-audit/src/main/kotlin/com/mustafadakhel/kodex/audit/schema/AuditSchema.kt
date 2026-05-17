package com.mustafadakhel.kodex.audit.schema

import com.mustafadakhel.kodex.audit.ActorType
import com.mustafadakhel.kodex.audit.EventResult
import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.Instant
import java.util.UUID

public class AuditSchema(private val prefix: String) : ExtensionSchema {

    public val auditEvents: AuditEventsTable = AuditEventsTable(prefix)

    public class AuditEventsTable(prefix: String) : TableDef("${prefix}audit_events", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val eventType: Column<String> = varchar("event_type", 100).index()
        public val timestamp: Column<Instant> = timestamp("timestamp").index()
        public val actorId: Column<UUID?> = uuid("actor_id").nullable().index()
        public val actorType: Column<ActorType> = enumByName<ActorType>("actor_type", 20)
        public val targetId: Column<UUID?> = uuid("target_id").nullable().index()
        public val targetType: Column<String?> = varchar("target_type", 100).nullable()
        public val result: Column<EventResult> = enumByName<EventResult>("result", 20)
        public val metadata: Column<String> = text("metadata")
        public val realmId: Column<String> = varchar("realm_id", 50).index()
        public val sessionId: Column<UUID?> = uuid("session_id").nullable()

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)
    }

    override fun tables(): List<TableDef> = listOf(auditEvents)
}
