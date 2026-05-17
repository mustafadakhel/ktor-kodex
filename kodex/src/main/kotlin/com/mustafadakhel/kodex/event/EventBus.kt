package com.mustafadakhel.kodex.event

public interface EventBus {
    public suspend fun publish(event: KodexEvent)
    public fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>)
    public fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>)
    public fun shutdown()
}
