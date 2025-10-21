package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.audit.AuditEvent

/**
 * Extension interface for audit logging.
 * Implementations can store, forward, or analyze audit events.
 */
public interface AuditHooks : RealmExtension {

    /**
     * Called when an audit event occurs.
     * Extensions can log, store, or forward the event.
     *
     * @param event The audit event to process
     */
    public suspend fun onAuditEvent(event: AuditEvent)
}
