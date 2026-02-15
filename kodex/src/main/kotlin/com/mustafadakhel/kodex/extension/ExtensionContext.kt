package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import kotlinx.datetime.TimeZone

public interface ExtensionContext {
    public val realm: Realm
    public val timeZone: TimeZone
    public val eventBus: EventBus
    public val rateLimiter: RateLimiter
}

internal data class ExtensionContextImpl(
    override val realm: Realm,
    override val timeZone: TimeZone,
    override val eventBus: EventBus,
    override val rateLimiter: RateLimiter
) : ExtensionContext

internal fun extensionContext(
    realm: Realm,
    timeZone: TimeZone,
    eventBus: EventBus,
    rateLimiter: RateLimiter
): ExtensionContext {
    return ExtensionContextImpl(realm, timeZone, eventBus, rateLimiter)
}
