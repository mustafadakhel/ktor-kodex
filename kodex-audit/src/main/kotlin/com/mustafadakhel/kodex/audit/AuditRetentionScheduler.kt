package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.observability.KodexLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Periodically invokes the audit retention service to purge old audit records.
 *
 * Follows the same coroutine-based scheduling pattern as SessionCleanupService.
 */
internal class AuditRetentionScheduler(
    private val retentionService: AuditRetentionService,
    private val checkInterval: Duration
) {
    @Volatile private var schedulerJob: Job? = null
    private val logger = KodexLogger.logger<AuditRetentionScheduler>()

    fun start(scope: CoroutineScope) {
        schedulerJob?.cancel()
        schedulerJob = scope.launch {
            delay(checkInterval) // Initial delay to allow database initialization
            while (isActive) {
                try {
                    val deletedCount = retentionService.cleanupOldAuditLogs()
                    logger.info("Audit retention cleanup completed: removed {} expired audit records", deletedCount)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Audit retention cleanup failed: ${e.message}", e)
                }
                delay(checkInterval)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }
}
