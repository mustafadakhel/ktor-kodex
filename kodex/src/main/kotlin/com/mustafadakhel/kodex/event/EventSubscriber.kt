package com.mustafadakhel.kodex.event

import kotlin.reflect.KClass

public interface EventSubscriber<T : KodexEvent> {
    public val eventType: KClass<out T>

    /** Called when event is published. Runs in isolated coroutine. */
    public suspend fun onEvent(event: T)

    /** Priority for ordering subscribers. Higher values run first. */
    public val priority: Int get() = 0
}
