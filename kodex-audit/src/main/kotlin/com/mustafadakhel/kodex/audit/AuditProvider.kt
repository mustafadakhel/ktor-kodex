package com.mustafadakhel.kodex.audit

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
