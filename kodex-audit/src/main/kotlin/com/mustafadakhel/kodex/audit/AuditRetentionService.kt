package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.util.kodexTransaction
import kotlinx.datetime.Clock
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
 *
 * Provides automatic deletion of audit logs older than the configured retention period
 * to prevent unbounded database growth and ensure GDPR compliance.
 *
 * Features:
 * - Configurable retention period (default: 90 days)
 * - Efficient batch deletion using indexed timestamp queries
 * - Returns count of deleted records for monitoring
 * - Thread-safe database operations
 *
 * GDPR Compliance:
 * - Audit logs are considered "logs of processing activities" under GDPR Article 30
 * - Retention should align with legal requirements (typically 6 months to 2 years)
 * - Default 90 days is conservative and suitable for most applications
 * - Organizations should configure based on their specific compliance needs
 */
public interface AuditRetentionService {

    /**
     * Deletes audit log entries older than the configured retention period.
     *
     * This operation:
     * - Uses indexed timestamp column for efficient queries
     * - Deletes all audit events with timestamp < (now - retentionPeriod)
     * - Is safe to run concurrently (uses database transactions)
     * - Returns count of deleted records for monitoring/logging
     *
     * @return Number of audit log entries deleted
     */
    public fun cleanupOldAuditLogs(): Int

    /**
     * Deletes audit logs older than a specific cutoff date.
     *
     * Useful for:
     * - Manual cleanup operations
     * - Testing retention policies
     * - One-time migrations or bulk deletions
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
            // Delete all audit logs with timestamp less than cutoff
            // Uses indexed timestamp column for efficient query
            // Convert LocalDateTime to Instant for comparison
            val cutoffInstant = cutoffDate.toInstant(timeZone)
            AuditLogs.deleteWhere {
                AuditLogs.timestamp less cutoffInstant
            }
        }
    }

    override fun getRetentionPeriod(): Duration {
        return retentionPeriod
    }

    /**
     * Calculates the cutoff date for audit log deletion.
     *
     * Formula: current_time - retention_period
     *
     * Example:
     * - Current time: 2025-10-21 12:00:00
     * - Retention period: 90 days
     * - Cutoff date: 2025-07-23 12:00:00
     * - All logs before 2025-07-23 will be deleted
     */
    private fun calculateCutoffDate(): LocalDateTime {
        val now = Clock.System.now()
        val cutoffInstant = now - retentionPeriod
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
