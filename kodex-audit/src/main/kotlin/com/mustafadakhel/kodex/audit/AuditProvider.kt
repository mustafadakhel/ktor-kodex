package com.mustafadakhel.kodex.audit

/**
 * Provider interface for audit logging implementations.
 * Implementations can log to console, file, database, or external services.
 *
 * Built-in implementations:
 * - [DatabaseAuditProvider] - Persists events to AuditLogs table (production)
 * - [ConsoleAuditProvider] - Logs to console (development/debugging)
 * - [NoOpAuditProvider] - Disables audit logging
 */
public interface AuditProvider {
    /**
     * Log an audit event.
     * This method should not throw exceptions - logging failures should be handled gracefully.
     */
    public suspend fun log(event: AuditEvent)
}

/**
 * Simple console-based audit provider for development and testing.
 */
public class ConsoleAuditProvider : AuditProvider {
    override suspend fun log(event: AuditEvent) {
        println("[AUDIT] ${event.timestamp} | ${event.eventType} | ${event.actorType} | ${event.result} | ${event.realmId}")
        if (event.metadata.isNotEmpty()) {
            println("        Metadata: ${event.metadata}")
        }
    }
}

/**
 * No-op audit provider that does nothing.
 * Useful for disabling audit logging.
 */
public class NoOpAuditProvider : AuditProvider {
    override suspend fun log(event: AuditEvent) {
        // Do nothing
    }
}
