package com.mustafadakhel.kodex.audit

import kotlinx.datetime.Instant
import java.util.*

public interface AuditService {
    public suspend fun log(event: AuditEvent)
    public suspend fun query(filter: AuditFilter = AuditFilter()): List<AuditEntry>
    public suspend fun export(
        filter: AuditFilter = AuditFilter(),
        format: ExportFormat = ExportFormat.JSON
    ): ByteArray
    public suspend fun count(filter: AuditFilter = AuditFilter()): Long
}

public data class AuditFilter(
    val eventTypes: List<String>? = null,
    val actorId: UUID? = null,
    val targetId: UUID? = null,
    val startDate: Instant? = null,
    val endDate: Instant? = null,
    val result: EventResult? = null,
    val realmId: String? = null,
    val limit: Int = 100,
    val offset: Int = 0
)

public data class AuditEntry(
    val id: UUID,
    val eventType: String,
    val timestamp: Instant,
    val actorId: UUID?,
    val actorType: ActorType,
    val targetId: UUID?,
    val targetType: String?,
    val result: EventResult,
    val metadata: Map<String, Any>,
    val realmId: String,
    val sessionId: UUID?
)

public enum class ExportFormat {
    JSON,
    CSV
}
