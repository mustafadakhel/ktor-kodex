package com.mustafadakhel.kodex.extension

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Extension interface for audit logging.
 * Implementations can store, forward, or analyze audit events.
 *
 * @deprecated This hook-based audit system is deprecated in favor of EventBus subscription.
 * Use EventSubscriber<KodexEvent> and register with EventBus instead.
 * This interface will be removed in a future major version.
 */
@Deprecated(
    message = "Use EventBus with EventSubscriber<KodexEvent> instead",
    replaceWith = ReplaceWith("EventSubscriber<KodexEvent>"),
    level = DeprecationLevel.WARNING
)
public interface AuditHooks : RealmExtension {

    /**
     * Called when an audit event occurs.
     * Extensions can log, store, or forward the event.
     *
     * Core only passes simple parameters - the extension builds any domain objects internally.
     *
     * @deprecated Use EventBus.publish() with typed events instead.
     */
    @Deprecated(
        message = "Use EventBus.publish() with typed events instead",
        level = DeprecationLevel.WARNING
    )
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
