@file:Suppress("DEPRECATION") // AuditHooks still supported during migration period

package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.audit.database.AuditLogs
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.extension.AuditHooks
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.PersistentExtension
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.Table
import java.util.UUID

/**
 * Audit logging extension that implements AuditHooks.
 * Delegates audit event logging to a configurable audit provider.
 *
 * If using DatabaseAuditProvider, this extension automatically registers
 * the AuditLogs table with the database engine.
 *
 * @deprecated logEvent() hook is deprecated. Use EventBus subscription instead.
 * The createSubscriber() method returns an EventSubscriber for the new event bus.
 */
public class AuditExtension internal constructor(
    private val provider: AuditProvider
) : AuditHooks, PersistentExtension, EventSubscriberProvider {

    override fun tables(): List<Table> = listOf(AuditLogs)

    /**
     * Returns event subscribers that should be registered with the EventBus.
     * This is called automatically during service initialization.
     */
    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> {
        return listOf(AuditEventSubscriber(provider))
    }

    /**
     * Creates an event subscriber for the event bus.
     * Register this subscriber with EventBus to receive all Kodex events.
     *
     * @deprecated Use getEventSubscribers() instead. Subscribers are now registered automatically.
     */
    @Deprecated(
        message = "Subscribers are now registered automatically via getEventSubscribers()",
        level = DeprecationLevel.WARNING
    )
    public fun createSubscriber(): AuditEventSubscriber {
        return AuditEventSubscriber(provider)
    }

    @Deprecated(
        message = "Use EventBus.publish() with typed events instead. This hook-based approach will be removed in a future version.",
        replaceWith = ReplaceWith("eventBus.publish(UserEvent.Created(...))"),
        level = DeprecationLevel.WARNING
    )
    override suspend fun logEvent(
        eventType: String,
        timestamp: Instant,
        realmId: String,
        actorId: UUID?,
        actorType: String,
        targetId: UUID?,
        targetType: String?,
        result: String,
        metadata: Map<String, String>,
        sessionId: UUID?
    ) {
        try {
            val event = AuditEvent(
                eventType = eventType,
                timestamp = timestamp,
                realmId = realmId,
                actorId = actorId,
                actorType = ActorType.fromString(actorType),
                targetId = targetId,
                targetType = targetType,
                result = EventResult.fromString(result),
                metadata = metadata,
                sessionId = sessionId
            )
            provider.log(event)
        } catch (e: Exception) {
            // Audit logging should never fail the main operation
            // Log to stderr and continue
            System.err.println("Failed to log audit event: ${e.message}")
        }
    }
}
