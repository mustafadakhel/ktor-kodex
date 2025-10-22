package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent

/**
 * Interface for extensions that want to subscribe to events via the EventBus.
 *
 * Extensions implementing this interface can provide one or more event subscribers
 * that will be automatically registered with the EventBus during service initialization.
 */
public interface EventSubscriberProvider : RealmExtension {

    /**
     * Returns a list of event subscribers that should be registered with the EventBus.
     *
     * @return List of event subscribers to register
     */
    public fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>>
}
