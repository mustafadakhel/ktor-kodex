package com.mustafadakhel.kodex.extension

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Extension interface for audit logging.
 * Implementations can store, forward, or analyze audit events.
 */
public interface AuditHooks : RealmExtension {

    /**
     * Called when an audit event occurs.
     * Extensions can log, store, or forward the event.
     *
     * Core only passes simple parameters - the extension builds any domain objects internally.
     */
    public suspend fun logEvent(
        eventType: String,
        timestamp: Instant,
        realmId: String,
        actorId: UUID? = null,
        actorType: String = "USER",
        targetId: UUID? = null,
        targetType: String? = null,
        result: String = "SUCCESS",
        metadata: Map<String, String> = emptyMap(),
        sessionId: UUID? = null
    )
}
