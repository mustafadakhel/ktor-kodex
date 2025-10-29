package com.mustafadakhel.kodex.routes.auth

import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.util.*


/**
 * Wrapper around a Ktor [Route] that exposes a user id retrieved via an
 * [IdScope].
 *
 * Example:
 * ```kotlin
 * authorizedRoute("/posts", CallParameterId) {
 *     get { userId ->
 *         // handle request for userId
 *     }
 * }
 * ```
 */
public class AuthorizedRoute(
    internal val route: Route,
    public val idScope: IdScope,
) : IdScope by idScope


@KtorDsl
/**
 * Creates a child [AuthorizedRoute] at the specified [path].
 *
 * Example:
 * ```kotlin
 * authorizedRoute("/posts", CallParameterId) {
 *     get { id -> /* ... */ }
 * }
 * ```
 */
public fun Route.authorizedRoute(
    path: String,
    idScope: IdScope,
    build: AuthorizedRoute.() -> Unit,
): AuthorizedRoute {
    val child = route(path) {}
    val authorizedRoute = AuthorizedRoute(
        route = child,
        idScope = idScope
    )
    authorizedRoute.build()
    return authorizedRoute
}

@KtorDsl
/**
 * Creates a nested [AuthorizedRoute] inheriting the current [IdScope].
 */
public fun AuthorizedRoute.route(
    path: String,
    build: AuthorizedRoute.() -> Unit,
): AuthorizedRoute = route.authorizedRoute(path = path, idScope = idScope, build = build)

@KtorDsl
/**
 * Registers a GET handler relative to [path] passing the resolved user id to
 * [body].
 */
public fun AuthorizedRoute.get(
    path: String,
    body: AuthorizedPipelineInterceptor
): Route {
    val idScope = idScope
    return route.get(
        path = path,
        body = { body(idScope.getOrFail(call)) }
    )
}

@KtorDsl
/**
 * Registers a GET handler on this route and provides the resolved user id to
 * [body].
 */
public fun AuthorizedRoute.get(
    body: AuthorizedPipelineInterceptor
): Route {
    val idScope = idScope
    return route.get(
        body = {
            body(idScope.getOrFail(call))
        }
    )
}

@KtorDsl
/**
 * Registers a PUT handler relative to [path] and provides the resolved user id
 * to [body].
 */
public fun AuthorizedRoute.put(
    path: String,
    body: AuthorizedPipelineInterceptor
): Route {
    val idScope = idScope
    return route.put(
        path = path,
        body = { body(idScope.getOrFail(call)) }
    )
}

@KtorDsl
/**
 * Registers a PUT handler and passes the resolved user id to [body].
 */
public fun AuthorizedRoute.put(
    body: AuthorizedPipelineInterceptor
): Route {
    val idScope = idScope
    return route.put(
        body = {
            body(idScope.getOrFail(call))
        }
    )
}

@KtorDsl
/**
 * Registers a POST handler relative to [path] passing the resolved user id to
 * [body].
 */
public fun AuthorizedRoute.post(
    path: String,
    body: AuthorizedPipelineInterceptor
): Route {
    val idScope = idScope
    return route.post(
        path = path,
        body = { body(idScope.getOrFail(call)) }
    )
}

@KtorDsl
/**
 * Registers a POST handler on this route and provides the resolved user id to
 * [body].
 */
public fun AuthorizedRoute.post(
    body: AuthorizedPipelineInterceptor
): Route {
    val idScope = idScope
    return route.post(
        body = {
            body(idScope.getOrFail(call))
        }
    )
}

/**
 * Interceptor invoked by authorized HTTP verbs providing the authenticated
 * user's id.
 */
public typealias AuthorizedPipelineInterceptor = suspend RoutingContext.(UUID) -> Unit
