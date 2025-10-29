package com.mustafadakhel.kodex.routes.auth

import io.ktor.server.application.*
import java.util.*

/**
 * Abstraction used to resolve a user id from an [ApplicationCall].
 */
public interface IdScope {
    /** Returns the user id or throws if it cannot be resolved. */
    public fun ApplicationCall.idOrFail(): UUID

    /** Returns the user id or `null` if it cannot be resolved. */
    public fun getOrNull(call: ApplicationCall): UUID?

    /** Called when a user id cannot be retrieved. */
    public fun idNotFound(): Nothing
}

/** Helper that invokes [IdScope.getOrNull] and throws when `null`. */
internal fun IdScope.getOrFail(
    call: ApplicationCall,
): UUID = getOrNull(call) ?: idNotFound()
