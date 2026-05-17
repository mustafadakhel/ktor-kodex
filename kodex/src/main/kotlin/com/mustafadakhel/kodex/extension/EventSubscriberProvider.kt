package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent

public interface EventSubscriberProvider : RealmExtension {
    public fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>>
}
