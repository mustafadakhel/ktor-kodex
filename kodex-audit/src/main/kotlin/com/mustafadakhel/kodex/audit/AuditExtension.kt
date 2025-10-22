package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.extension.AuditHooks
import com.mustafadakhel.kodex.extension.PersistentExtension
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Audit logging extension that implements AuditHooks.
 * Delegates audit event logging to a configurable audit provider.
 *
 * If using DatabaseAuditProvider, this extension automatically registers
 * the AuditLogs table with the database engine.
 */
public class AuditExtension internal constructor(
    private val provider: AuditProvider
) : AuditHooks, PersistentExtension {

    override fun tables(): List<Table> = listOf(AuditLogs)

    override suspend fun logEvent(
        eventType: String,
        timestamp: Instant,
        realmId: String,
        actorId: UUID?,
        actorType: String,
        targetId: UUID?,
        targetType: String?,
        result: String,
        metadata: Map<String, String>,
        sessionId: UUID?
    ) {
        try {
            val event = AuditEvent(
                eventType = eventType,
                timestamp = timestamp,
                realmId = realmId,
                actorId = actorId,
                actorType = ActorType.fromString(actorType),
                targetId = targetId,
                targetType = targetType,
                result = EventResult.fromString(result),
                metadata = metadata,
                sessionId = sessionId
            )
            provider.log(event)
        } catch (e: Exception) {
            // Audit logging should never fail the main operation
            // Log to stderr and continue
            System.err.println("Failed to log audit event: ${e.message}")
        }
    }
}
