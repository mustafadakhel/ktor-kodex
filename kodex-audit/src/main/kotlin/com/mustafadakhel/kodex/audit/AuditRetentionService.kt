package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.util.kodexTransaction
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Service for managing audit log retention and cleanup.
 */
public interface AuditRetentionService {

    /**
     * Deletes audit log entries older than the configured retention period.
     *
     * @return Number of audit log entries deleted
     */
    public fun cleanupOldAuditLogs(): Int

    /**
     * Deletes audit logs older than a specific cutoff date.
     *
     * @param cutoffDate Delete all audit logs with timestamp before this date
     * @return Number of audit log entries deleted
     */
    public fun cleanupAuditLogsOlderThan(cutoffDate: LocalDateTime): Int

    /**
     * Gets the current retention period configuration.
     *
     * @return Retention period duration
     */
    public fun getRetentionPeriod(): Duration
}

/**
 * Default implementation of audit retention service.
 *
 * @property retentionPeriod How long to keep audit logs before deletion
 * @property timeZone Time zone used for timestamp calculations
 */
internal class DefaultAuditRetentionService(
    private val retentionPeriod: Duration,
    private val timeZone: TimeZone
) : AuditRetentionService {

    override fun cleanupOldAuditLogs(): Int {
        val cutoffDate = calculateCutoffDate()
        return cleanupAuditLogsOlderThan(cutoffDate)
    }

    override fun cleanupAuditLogsOlderThan(cutoffDate: LocalDateTime): Int {
        return kodexTransaction {
            val cutoffInstant = cutoffDate.toInstant(timeZone)
            AuditLogs.deleteWhere {
                AuditLogs.timestamp less cutoffInstant
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
 * @param timeZone Time zone for timestamp calculations
 * @return Configured audit retention service
 */
public fun auditRetentionService(
    config: AuditRetentionConfig,
    timeZone: TimeZone = TimeZone.UTC
): AuditRetentionService {
    return DefaultAuditRetentionService(
        retentionPeriod = config.retentionPeriod,
        timeZone = timeZone
    )
}
