package com.mustafadakhel.kodex.audit

/**
 * Provider interface for audit logging implementations.
 * Implementations can log to console, file, database, or external services.
 *
 * Built-in implementations:
 * - [DatabaseAuditProvider] - Persists events to AuditLogs table (production)
 * - ConsoleAuditProvider - Logs to console (development/debugging)
 * - NoOpAuditProvider - Disables audit logging
 */
public interface AuditProvider {
    /**
     * Log an audit event.
     * This method should not throw exceptions - logging failures should be handled gracefully.
     */
    public suspend fun log(event: AuditEvent)
}
