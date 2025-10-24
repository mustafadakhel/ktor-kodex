package com.mustafadakhel.kodex.event

/** Pub/sub event bus for typed events. */
public interface EventBus {

    /** Publishes an event asynchronously to all subscribers. */
    public suspend fun publish(event: KodexEvent)

    /** Registers a subscriber for a specific event type. */
    public fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>)

    /** Unregisters a subscriber. */
    public fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>)

    /** Shuts down the event bus and cancels pending operations. */
    public fun shutdown()
}
