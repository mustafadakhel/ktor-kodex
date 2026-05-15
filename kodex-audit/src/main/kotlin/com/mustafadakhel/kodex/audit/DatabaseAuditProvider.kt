package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert

public class DatabaseAuditProvider(
    private val db: KodexDatabase,
    private val schema: AuditSchema
) : AuditProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val auditEvents = schema.auditEvents

    override suspend fun log(event: AuditEvent) {
        try {
            db.transaction {
                val sanitized = MetadataSanitizer.sanitize(event.metadata)
                val stringified = sanitized.mapValues { (_, v) -> v.toString() }
                val metadataJson = json.encodeToString(stringified)

                auditEvents.insert {
                    it[auditEvents.eventType] = event.eventType
                    it[auditEvents.timestamp] = event.timestamp
                    it[auditEvents.actorId] = event.actorId
                    it[auditEvents.actorType] = event.actorType
                    it[auditEvents.targetId] = event.targetId
                    it[auditEvents.targetType] = event.targetType
                    it[auditEvents.result] = event.result
                    it[auditEvents.metadata] = metadataJson
                    it[auditEvents.realmId] = event.realmId
                    it[auditEvents.sessionId] = event.sessionId
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to persist audit event to database: ${e.message}")
            e.printStackTrace()
        }
    }
}
