package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.Realm
import kotlinx.datetime.TimeZone

/**
 * Context provided to extensions during initialization.
 * Provides access to shared resources and realm configuration.
 *
 * Extensions receive this context in their config's build() method,
 * allowing them to access necessary dependencies without tight coupling.
 *
 * Example:
 * ```kotlin
 * class RateLimitConfig : ExtensionConfig() {
 *     var requestsPerMinute: Int = 60
 *
 *     override fun build(context: ExtensionContext): RealmExtension {
 *         // Access realm-specific configuration
 *         val realm = context.realm
 *         val timeZone = context.timeZone
 *
 *         return RateLimitExtension(
 *             realm = realm,
 *             timeZone = timeZone,
 *             requestsPerMinute = requestsPerMinute
 *         )
 *     }
 * }
 * ```
 */
public interface ExtensionContext {
    /**
     * The realm this extension is being configured for.
     * Use this to scope extension behavior to specific realms.
     */
    public val realm: Realm

    /**
     * The time zone configured for this realm.
     * Use for timestamp calculations and date/time formatting.
     */
    public val timeZone: TimeZone
}

/**
 * Internal implementation of ExtensionContext.
 */
internal data class ExtensionContextImpl(
    override val realm: Realm,
    override val timeZone: TimeZone
) : ExtensionContext

/**
 * Creates an ExtensionContext with the given realm and time zone.
 * This is a public factory function used by the core during extension initialization.
 */
public fun extensionContext(realm: Realm, timeZone: TimeZone): ExtensionContext {
    return ExtensionContextImpl(realm, timeZone)
}
