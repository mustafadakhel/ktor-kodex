package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

public interface AuditRetentionService {
    public fun cleanupOldAuditLogs(): Int
    public fun cleanupAuditLogsOlderThan(cutoffDate: LocalDateTime): Int
    public fun getRetentionPeriod(): Duration
}

internal class DefaultAuditRetentionService(
    private val retentionPeriod: Duration,
    private val timeZone: TimeZone,
    private val realmId: String
) : AuditRetentionService {

    override fun cleanupOldAuditLogs(): Int {
        val cutoffDate = calculateCutoffDate()
        return cleanupAuditLogsOlderThan(cutoffDate)
    }

    override fun cleanupAuditLogsOlderThan(cutoffDate: LocalDateTime): Int {
        return kodexTransaction {
            val cutoffInstant = cutoffDate.toInstant(timeZone)
            AuditLogs.deleteWhere {
                (AuditLogs.realmId eq realmId) and (AuditLogs.timestamp less cutoffInstant)
            }
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

/**
 * Configuration for audit log retention policy.
 *
 * @property retentionPeriod How long to keep audit logs (default: 90 days)
 * @property enabled Whether automatic cleanup is enabled (default: true)
 */
public data class AuditRetentionConfig(
    val retentionPeriod: Duration = 90.days,
    val enabled: Boolean = true
)

/**
 * Creates an audit retention service with the specified configuration.
 *
 * @param config Retention policy configuration
 * @param realmId The realm whose audit logs should be cleaned up
 * @param timeZone Time zone for timestamp calculations
 * @return Configured audit retention service
 */
public fun auditRetentionService(
    config: AuditRetentionConfig,
    realmId: String,
    timeZone: TimeZone = TimeZone.UTC
): AuditRetentionService {
    return DefaultAuditRetentionService(
        retentionPeriod = config.retentionPeriod,
        timeZone = timeZone,
        realmId = realmId
    )
}
