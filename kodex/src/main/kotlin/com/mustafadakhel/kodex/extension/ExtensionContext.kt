package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import kotlinx.datetime.TimeZone

/** Context provided to extensions during initialization. */
public interface ExtensionContext {
    public val realm: Realm
    public val timeZone: TimeZone
    public val userRepository: UserRepository
}

internal data class ExtensionContextImpl(
    override val realm: Realm,
    override val timeZone: TimeZone,
    override val userRepository: UserRepository
) : ExtensionContext

internal fun extensionContext(
    realm: Realm,
    timeZone: TimeZone,
    userRepository: UserRepository
): ExtensionContext {
    return ExtensionContextImpl(realm, timeZone, userRepository)
}
