package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.Realm
import kotlinx.datetime.TimeZone

/** Context provided to extensions during initialization. */
public interface ExtensionContext {
    public val realm: Realm
    public val timeZone: TimeZone
}

internal data class ExtensionContextImpl(
    override val realm: Realm,
    override val timeZone: TimeZone
) : ExtensionContext

public fun extensionContext(realm: Realm, timeZone: TimeZone): ExtensionContext {
    return ExtensionContextImpl(realm, timeZone)
}
