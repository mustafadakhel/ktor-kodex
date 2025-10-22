package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.extension.AuditHooks
import com.mustafadakhel.kodex.extension.PersistentExtension
import org.jetbrains.exposed.sql.Table

/**
 * Audit logging extension that implements AuditHooks.
 * Delegates audit event logging to a configurable audit provider.
 *
 * If using DatabaseAuditProvider, this extension automatically registers
 * the AuditLogs table with the database engine.
 */
public class AuditExtension internal constructor(
    private val provider: AuditProvider
) : AuditHooks, PersistentExtension {

    override fun tables(): List<Table> = listOf(AuditLogs)

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
