package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.PersistentExtension
import org.jetbrains.exposed.sql.Table

/**
 * Audit logging extension for Kodex.
 * Delegates audit event logging to a configurable audit provider via the event bus.
 *
 * If using DatabaseAuditProvider, this extension automatically registers
 * the AuditLogs table with the database engine.
 */
public class AuditExtension internal constructor(
    private val provider: AuditProvider
) : PersistentExtension, EventSubscriberProvider {

    override fun tables(): List<Table> = listOf(AuditLogs)

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> {
        return listOf(AuditEventSubscriber(provider))
    }
}
