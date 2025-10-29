package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.extension.AuditHooks

/**
 * Audit logging extension that implements AuditHooks.
 * Delegates audit event logging to a configurable audit provider.
 */
public class AuditExtension internal constructor(
    private val provider: AuditProvider
) : AuditHooks {

    override suspend fun onAuditEvent(event: AuditEvent) {
        try {
            provider.log(event)
        } catch (e: Exception) {
            // Audit logging should never fail the main operation
            // Log to stderr and continue
            System.err.println("Failed to log audit event: ${e.message}")
        }
    }
}
