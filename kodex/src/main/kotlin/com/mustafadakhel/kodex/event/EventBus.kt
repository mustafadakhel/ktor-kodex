package com.mustafadakhel.kodex.event

/**
 * Central event bus for publishing and subscribing to events.
 *
 * The event bus provides a decoupled pub/sub system where publishers
 * emit events without knowing about subscribers, and subscribers register
 * interest in specific event types.
 *
 * Key features:
 * - Non-blocking publishing (events queued for async processing)
 * - Type-safe event routing
 * - Subscriber isolation (failures don't propagate)
 * - Security (only registered extensions can subscribe)
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
