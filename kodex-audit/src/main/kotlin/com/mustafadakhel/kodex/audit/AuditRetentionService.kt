@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.schema.AuditSchema
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.less
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

public interface AuditRetentionService {
    public fun cleanupOldAuditLogs(): Int
    public fun cleanupAuditLogsOlderThan(cutoffDate: LocalDateTime): Int
    public fun getRetentionPeriod(): Duration
}

internal class DefaultAuditRetentionService(
    private val db: KodexDatabase,
    private val schema: AuditSchema,
    private val retentionPeriod: Duration,
    private val timeZone: TimeZone,
    private val realmId: String
) : AuditRetentionService {

    private val auditEvents = schema.auditEvents

    override fun cleanupOldAuditLogs(): Int {
        val cutoffDate = calculateCutoffDate()
        return cleanupAuditLogsOlderThan(cutoffDate)
    }

    override fun cleanupAuditLogsOlderThan(cutoffDate: LocalDateTime): Int {
        return db.transaction {
            val cutoffInstant = cutoffDate.toInstant(timeZone)
            deleteFrom(auditEvents)
                .where { (auditEvents.realmId eq realmId) and (auditEvents.timestamp less cutoffInstant) }
                .execute()
        }
    }

    override fun getRetentionPeriod(): Duration {
        return retentionPeriod
    }

    private fun calculateCutoffDate(): LocalDateTime {
        val nowInstant = CurrentKotlinInstant
        val cutoffInstant = nowInstant - retentionPeriod
        return cutoffInstant.toLocalDateTime(timeZone)
    }
}

public data class AuditRetentionConfig(
    val retentionPeriod: Duration = 90.days,
    val enabled: Boolean = true
)

public fun auditRetentionService(
    db: KodexDatabase,
    schema: AuditSchema,
    config: AuditRetentionConfig,
    realmId: String,
    timeZone: TimeZone = TimeZone.UTC
): AuditRetentionService {
    return DefaultAuditRetentionService(
        db = db,
        schema = schema,
        retentionPeriod = config.retentionPeriod,
        timeZone = timeZone,
        realmId = realmId
    )
}
