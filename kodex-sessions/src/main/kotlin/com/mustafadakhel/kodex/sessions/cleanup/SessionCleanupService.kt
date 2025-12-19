package com.mustafadakhel.kodex.sessions.cleanup

import com.mustafadakhel.kodex.observability.KodexLogger
import com.mustafadakhel.kodex.sessions.SessionConfig
import com.mustafadakhel.kodex.sessions.SessionService
import kotlinx.coroutines.*
import kotlin.time.Duration

public class SessionCleanupService(
    private val sessionService: SessionService,
    private val config: SessionConfig
) {
    private var cleanupJob: Job? = null
    private val logger = KodexLogger.logger<SessionCleanupService>()

    /**
     * Start the cleanup service with periodic execution.
     */
    public fun start(scope: CoroutineScope) {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(config.cleanupInterval) // Initial delay to allow database initialization
            while (isActive) {
                try {
                    cleanup()
                } catch (e: Exception) {
                    logger.error("Session cleanup failed: ${e.message}", e)
                }
                delay(config.cleanupInterval)
            }
        }
    }

    /**
     * Stop the cleanup service.
     */
    public fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    /**
     * Manually trigger cleanup.
     */
    public suspend fun cleanup() {
        val expiredCount = sessionService.archiveExpiredSessions()
        val historyCount = sessionService.cleanupOldHistory()
        logger.info("Session cleanup completed: archived {} expired sessions, removed {} old history entries", expiredCount, historyCount)
    }
}
