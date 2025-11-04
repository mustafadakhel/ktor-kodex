package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.model.Realm
import kotlinx.datetime.TimeZone

/** Context provided to extensions during initialization. */
public interface ExtensionContext {
    public val realm: Realm
    public val timeZone: TimeZone
    public val eventBus: EventBus
}

internal data class ExtensionContextImpl(
    override val realm: Realm,
    override val timeZone: TimeZone,
    override val eventBus: EventBus
) : ExtensionContext

internal fun extensionContext(
    realm: Realm,
    timeZone: TimeZone,
    eventBus: EventBus
): ExtensionContext {
    return ExtensionContextImpl(realm, timeZone, eventBus)
}
