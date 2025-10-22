package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogDao
import com.mustafadakhel.kodex.util.kodexTransaction

/**
 * Database-backed audit provider that persists events to the AuditLogs table.
 *
 * This provider ensures all audit events are stored persistently in the database
 * for compliance, forensics, and audit trail purposes.
 *
 * Features:
 * - Atomic writes with transaction support
 * - Automatic metadata sanitization via AuditLogDao
 * - Graceful error handling (logging never fails main operations)
 */
public class DatabaseAuditProvider : AuditProvider {

    override suspend fun log(event: AuditEvent) {
        try {
            kodexTransaction {
                AuditLogDao.new {
                    this.eventType = event.eventType
                    this.timestamp = event.timestamp
                    this.actorId = event.actorId
                    this.actorType = event.actorType
                    this.targetId = event.targetId
                    this.targetType = event.targetType
                    this.result = event.result
                    this.metadata = event.metadata
                    this.realmId = event.realmId
                    this.sessionId = event.sessionId
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to persist audit event to database: ${e.message}")
            e.printStackTrace()
        }
    }
}
