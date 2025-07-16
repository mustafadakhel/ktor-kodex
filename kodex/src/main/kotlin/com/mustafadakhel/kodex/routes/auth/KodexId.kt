package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.ktor.server.application.*
import io.ktor.server.routing.*
import java.util.*

/**
 * [IdScope] that obtains the user id from the authenticated [KodexPrincipal].
 */
public object KodexId : IdScope {
    override fun ApplicationCall.idOrFail(): UUID {
        return getOrNull(this) ?: idNotFound()
    }

    override fun getOrNull(call: ApplicationCall): UUID? = call.kodex?.userId

    override fun idNotFound(): Nothing = throw KodexThrowable.Authorization.SuspiciousToken(
        "Kodex principal is missing or malformed"
    )
}

/**
 * Convenience helper that creates an [AuthorizedRoute] at `/me` using the
 * authenticated principal's id.
 */
public fun Route.me(build: AuthorizedRoute.() -> Unit) {
    authorizedRoute("/me", KodexId, build)
}
