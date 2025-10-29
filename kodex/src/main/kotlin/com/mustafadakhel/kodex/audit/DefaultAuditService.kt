package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.model.database.AuditLogDao
import com.mustafadakhel.kodex.model.database.AuditLogs
import com.mustafadakhel.kodex.util.exposedTransaction
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.sql.Timestamp
import kotlin.time.Duration

/**
 * Configuration for the audit service.
 *
 * @property enabled Whether audit logging is enabled
 * @property queueCapacity Maximum events in memory queue
 * @property batchSize Number of events to write per batch
 * @property flushInterval Maximum time between batch flushes
 * @property retentionPeriod How long to retain audit logs (null = forever)
 */
internal data class AuditConfig(
    val enabled: Boolean,
    val queueCapacity: Int,
    val batchSize: Int,
    val flushInterval: Duration,
    val retentionPeriod: Duration?
)

/**
 * Default implementation of AuditService with async queue and database persistence.
 *
 * This implementation:
 * - Uses an in-memory queue for non-blocking event capture
 * - Batch writes to database for performance
 * - Provides query and export capabilities
 * - Never throws exceptions (audit failures shouldn't crash the app)
 *
 * @property queue Async queue for event processing
 * @property config Audit service configuration
 */
internal class DefaultAuditService(
    private val queue: AuditQueue,
    private val config: AuditConfig
) : AuditService {

    override suspend fun log(event: AuditEvent) {
        if (!config.enabled) return
        queue.enqueue(event)
    }

    override suspend fun query(filter: AuditFilter): List<AuditEntry> {
        return exposedTransaction {
            AuditLogs.selectAll().let { query ->
                var result = query

                // Apply filters using Exposed's type-safe DSL
                filter.eventTypes?.let { types ->
                    result = result.where { AuditLogs.eventType inList types }
                }

                filter.actorId?.let { actor ->
                    result = result.adjustWhere { AuditLogs.actorId eq actor }
                }

                filter.targetId?.let { target ->
                    result = result.adjustWhere { AuditLogs.targetId eq target }
                }

                filter.startDate?.let { start ->
                    result = result.adjustWhere { AuditLogs.timestamp greaterEq start }
                }

                filter.endDate?.let { end ->
                    result = result.adjustWhere { AuditLogs.timestamp lessEq end }
                }

                filter.result?.let { r ->
                    result = result.adjustWhere { AuditLogs.result eq r }
                }

                filter.realmId?.let { realm ->
                    result = result.adjustWhere { AuditLogs.realmId eq realm }
                }

                // Order by timestamp descending (newest first)
                result
                    .orderBy(AuditLogs.timestamp to SortOrder.DESC)
                    .limit(filter.limit)
                    .offset(filter.offset.toLong())
                    .map { AuditLogDao.wrapRow(it).toAuditEntry() }
            }
        }
    }

    override suspend fun export(filter: AuditFilter, format: ExportFormat): ByteArray {
        val entries = query(filter)
        return when (format) {
            ExportFormat.JSON -> exportJson(entries)
            ExportFormat.CSV -> exportCsv(entries)
        }
    }

    override suspend fun count(filter: AuditFilter): Long {
        return exposedTransaction {
            AuditLogs.selectAll().let { query ->
                var result = query

                // Apply same filters as query()
                filter.eventTypes?.let { types ->
                    result = result.where { AuditLogs.eventType inList types }
                }

                filter.actorId?.let { actor ->
                    result = result.adjustWhere { AuditLogs.actorId eq actor }
                }

                filter.targetId?.let { target ->
                    result = result.adjustWhere { AuditLogs.targetId eq target }
                }

                filter.startDate?.let { start ->
                    result = result.adjustWhere { AuditLogs.timestamp greaterEq start }
                }

                filter.endDate?.let { end ->
                    result = result.adjustWhere { AuditLogs.timestamp lessEq end }
                }

                filter.result?.let { r ->
                    result = result.adjustWhere { AuditLogs.result eq r }
                }

                filter.realmId?.let { realm ->
                    result = result.adjustWhere { AuditLogs.realmId eq realm }
                }

                result.count()
            }
        }
    }

    /**
     * Export audit entries to JSON format.
     *
     * Format: Array of audit entry objects with full metadata.
     */
    private fun exportJson(entries: List<AuditEntry>): ByteArray {
        // Use kotlinx.serialization for clean JSON encoding
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        // Convert to serializable map structure
        val serializable = entries.map { entry ->
            mapOf(
                "id" to entry.id.toString(),
                "eventType" to entry.eventType,
                "timestamp" to entry.timestamp.toString(),
                "actorId" to entry.actorId?.toString(),
                "actorType" to entry.actorType.name,
                "targetId" to entry.targetId?.toString(),
                "targetType" to entry.targetType,
                "result" to entry.result.name,
                "metadata" to entry.metadata,
                "realmId" to entry.realmId,
                "sessionId" to entry.sessionId?.toString()
            )
        }

        return json.encodeToString(serializable).toByteArray()
    }

    /**
     * Export audit entries to CSV format.
     *
     * Format: Headers + rows with core fields (metadata as JSON string).
     */
    private fun exportCsv(entries: List<AuditEntry>): ByteArray {
        val csv = buildString {
            // Headers
            appendLine("id,eventType,timestamp,actorId,actorType,targetId,targetType,result,realmId,sessionId,metadata")

            // Rows
            entries.forEach { entry ->
                val metadataJson = Json.encodeToString(entry.metadata)
                    .replace("\"", "\"\"") // Escape quotes for CSV

                appendLine(
                    listOf(
                        entry.id,
                        entry.eventType,
                        entry.timestamp,
                        entry.actorId ?: "",
                        entry.actorType.name,
                        entry.targetId ?: "",
                        entry.targetType ?: "",
                        entry.result.name,
                        entry.realmId,
                        entry.sessionId ?: "",
                        "\"$metadataJson\"" // Quoted for CSV
                    ).joinToString(",")
                )
            }
        }

        return csv.toByteArray()
    }
}

/**
 * Factory function to create a configured AuditService instance.
 *
 * @param config Audit configuration
 * @return Configured audit service with started queue processor
 */
internal fun auditService(config: AuditConfig): AuditService {
    val writer = ExposedAuditWriter()
    val queue = InMemoryAuditQueue(
        writer = writer,
        queueCapacity = config.queueCapacity,
        batchSize = config.batchSize,
        flushInterval = config.flushInterval
    )

    // Start the queue processor
    queue.start()

    return DefaultAuditService(queue, config)
}
