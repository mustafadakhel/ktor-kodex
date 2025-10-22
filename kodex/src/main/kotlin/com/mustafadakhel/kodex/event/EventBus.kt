package com.mustafadakhel.kodex.event

/**
 * Central event bus for publishing and subscribing to events.
 *
 * The event bus provides a decoupled pub/sub system where publishers
 * emit events without knowing about subscribers, and subscribers register
 * interest in specific event types.
 *
 * ## Key Features:
 * - Non-blocking publishing (events queued for async processing)
 * - Type-safe event routing
 * - Subscriber isolation (failures don't propagate)
 * - Security (only registered extensions can subscribe)
 *
 * ## Usage Pattern:
 *
 * **Publishers (Service Layer):**
 * ```kotlin
 * eventBus.publish(
 *     UserEvent.Created(
 *         eventId = UUID.randomUUID(),
 *         timestamp = Clock.System.now(),
 *         realmId = realm.owner,
 *         userId = user.id,
 *         email = email,
 *         phone = phone
 *     )
 * )
 * ```
 *
 * **Subscribers (Extensions):**
 * ```kotlin
 * class MyExtension : EventSubscriberProvider {
 *     override fun getEventSubscribers() = listOf(
 *         MyEventSubscriber()
 *     )
 * }
 *
 * class MyEventSubscriber : EventSubscriber<UserEvent> {
 *     override val eventType = UserEvent::class
 *     override suspend fun onEvent(event: UserEvent) {
 *         // Handle event
 *     }
 * }
 * ```
 *
 * ## Migration from AuditHooks:
 *
 * The old hook-based system (AuditHooks.logEvent) is deprecated.
 * Use EventBus.publish() with typed events instead.
 *
 * **Before:**
 * ```kotlin
 * hookExecutor.logAuditEvent(
 *     eventType = "USER_CREATED",
 *     timestamp = timestamp,
 *     actorType = "SYSTEM",
 *     targetId = userId,
 *     result = "SUCCESS",
 *     metadata = mapOf(...),
 *     realmId = realm.owner
 * )
 * ```
 *
 * **After:**
 * ```kotlin
 * eventBus.publish(
 *     UserEvent.Created(
 *         eventId = UUID.randomUUID(),
 *         timestamp = timestamp,
 *         realmId = realm.owner,
 *         userId = userId,
 *         email = email,
 *         phone = phone
 *     )
 * )
 * ```
 */
public interface EventBus {

    /**
     * Publish an event to all registered subscribers.
     *
     * This method is non-blocking and returns immediately after queueing
     * the event for processing. Subscribers are notified asynchronously.
     *
     * @param event The event to publish
     */
    public suspend fun publish(event: KodexEvent)

    /**
     * Register a subscriber for a specific event type.
     *
     * The subscriber will be notified whenever an event of the subscribed
     * type (or subtype) is published.
     *
     * @param subscriber The subscriber to register
     * @throws IllegalArgumentException if subscriber is not a registered extension
     */
    public fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>)

    /**
     * Unregister a subscriber.
     *
     * The subscriber will no longer receive events.
     *
     * @param subscriber The subscriber to unregister
     */
    public fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>)
}
