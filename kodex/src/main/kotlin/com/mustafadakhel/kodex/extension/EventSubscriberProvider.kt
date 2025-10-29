package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent

/** Extensions can provide event subscribers through this interface. */
public interface EventSubscriberProvider : RealmExtension {

    /** Returns event subscribers to register with the EventBus. */
    public fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>>
}
