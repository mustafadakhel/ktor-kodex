@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.observability.KodexLogger
import com.mustafadakhel.kodex.schema.KodexDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public class DatabaseAuditProvider(
    private val db: KodexDatabase,
    private val schema: AuditSchema
) : AuditProvider {

    private val logger = KodexLogger.logger<DatabaseAuditProvider>()
    private val json = Json { ignoreUnknownKeys = true }
    private val auditEvents = schema.auditEvents

    override suspend fun log(event: AuditEvent) {
        try {
            db.transaction {
                val sanitized = MetadataSanitizer.sanitize(event.metadata)
                val stringified = sanitized.mapValues { (_, v) -> v.toString() }
                val metadataJson = json.encodeToString(stringified)

                insertInto(auditEvents) {
                    this[auditEvents.eventType] = event.eventType
                    this[auditEvents.timestamp] = event.timestamp
                    this[auditEvents.actorId] = event.actorId
                    this[auditEvents.actorType] = event.actorType
                    this[auditEvents.targetId] = event.targetId
                    this[auditEvents.targetType] = event.targetType
                    this[auditEvents.result] = event.result
                    this[auditEvents.metadata] = metadataJson
                    this[auditEvents.realmId] = event.realmId
                    this[auditEvents.sessionId] = event.sessionId
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to persist audit event: ${e.message}", e)
        }
    }
}
