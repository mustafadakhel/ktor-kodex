package com.mustafadakhel.kodex.routes.auth

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import com.mustafadakhel.kodex.util.toUuidOrNull
import java.util.*

/**
 * [IdScope] that reads a UUID from the `id` path parameter.
 *
 * Example usage:
 * ```kotlin
 * route("/users") {
 *     id { get { userId -> /* ... */ } }
 * }
 * ```
 */
public object CallParameterId : IdScope {
    override fun ApplicationCall.idOrFail(): UUID {
        return getOrNull(this) ?: idNotFound()
    }

    override fun getOrNull(call: ApplicationCall): UUID? = call.parameters.getOrFail("id").toUuidOrNull()

    override fun idNotFound(): Nothing = throw BadRequestException("Invalid ID")
}

/**
 * Creates an [AuthorizedRoute] that exposes the `id` path segment as a UUID.
 */
public fun Route.id(build: AuthorizedRoute.() -> Unit) {
    authorizedRoute("/{id}", CallParameterId, build = build)
}
