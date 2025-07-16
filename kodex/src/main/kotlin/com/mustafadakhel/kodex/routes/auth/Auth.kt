package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.kodex
import com.mustafadakhel.kodex.model.Realm
import io.ktor.server.routing.*

/**
 * Authenticates this [Route] for the supplied [realms].
 *
 * ### Example
 * ```kotlin
 * routing {
 *     authenticateFor(Realm.Main) {
 *         get("/secure") { /* ... */ }
 *     }
 * }
 * ```
 */
public fun Route.authenticateFor(
    vararg realms: Realm,
    block: Route.() -> Unit,
) {
    require(realms.isNotEmpty()) { "At least one realm must be provided" }

    val kodex = application.kodex
    kodex.authenticate(
        realms = realms.asList(),
        route = this,
        block = block,
    )
}
