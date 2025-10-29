package com.mustafadakhel.kodex.event

import kotlin.reflect.KClass

/** Interface for extensions that receive events. */
public interface EventSubscriber<T : KodexEvent> {

    /** The event type this subscriber handles. */
    public val eventType: KClass<out T>

    /** Called when event is published. Runs in isolated coroutine. */
    public suspend fun onEvent(event: T)

    /** Priority for ordering subscribers. Higher values run first. */
    public val priority: Int get() = 0
}
