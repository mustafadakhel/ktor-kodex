package com.mustafadakhel.kodex.extension

import org.jetbrains.exposed.sql.Table
import kotlin.reflect.KClass

/**
 * Base interface for realm extensions.
 * Extensions can add custom functionality like validation, rate limiting, etc.
 */
public interface RealmExtension

/**
 * Interface for extensions that require database persistence.
 * Extensions implementing this interface declare their database tables,
 * which will be created during core initialization.
 *
 * Example:
 * ```kotlin
 * class AccountLockoutExtension : UserLifecycleHooks, PersistentExtension {
 *     override fun tables(): List<Table> = listOf(
 *         FailedLoginAttempts,
 *         AccountLockouts
 *     )
 * }
 * ```
 */
public interface PersistentExtension : RealmExtension {
    /**
     * Returns the list of database tables this extension requires.
     * Core will create these tables during initialization.
     */
    public fun tables(): List<Table>
}

/**
 * Registry for realm extensions.
 * Allows looking up extensions by their type.
 * Supports multiple extensions of the same type for chaining.
 */
public class ExtensionRegistry internal constructor(
    private val extensions: Map<KClass<out RealmExtension>, List<RealmExtension>>
) {
    /**
     * Retrieves all extensions of a given type.
     * Returns extensions in registration order for chaining.
     *
     * @param extensionClass The class of the extension to retrieve
     * @return List of extension instances (empty if none registered)
     */
    public fun <T : RealmExtension> getAllOfType(extensionClass: KClass<T>): List<T> {
        @Suppress("UNCHECKED_CAST")
        return extensions[extensionClass] as? List<T> ?: emptyList()
    }

    /**
     * Retrieves the first extension of a given type.
     * Convenience method for when only one extension is expected.
     *
     * @param extensionClass The class of the extension to retrieve
     * @return The first extension instance, or null if not registered
     */
    public fun <T : RealmExtension> get(extensionClass: KClass<T>): T? {
        return getAllOfType(extensionClass).firstOrNull()
    }

    /**
     * Checks if any extension of the given type is registered.
     *
     * @param extensionClass The class of the extension to check
     * @return true if at least one extension is registered
     */
    public fun <T : RealmExtension> has(extensionClass: KClass<T>): Boolean {
        return extensions.containsKey(extensionClass) && extensions[extensionClass]?.isNotEmpty() == true
    }

    /**
     * Collects all database tables from persistent extensions.
     * Used by core during initialization to create extension tables.
     *
     * @return List of tables required by all registered persistent extensions
     */
    public fun getTables(): List<Table> {
        return extensions.values
            .flatten()
            .filterIsInstance<PersistentExtension>()
            .flatMap { it.tables() }
            .distinct()
    }

    public companion object {
        /**
         * Creates an empty extension registry.
         */
        public fun empty(): ExtensionRegistry = ExtensionRegistry(emptyMap())

        /**
         * Creates an extension registry from a map.
         * Converts single extension to list for internal storage.
         */
        public fun from(extensions: Map<KClass<out RealmExtension>, RealmExtension>): ExtensionRegistry {
            val extensionLists = extensions.mapValues { (_, extension) -> listOf(extension) }
            return ExtensionRegistry(extensionLists)
        }

        /**
         * Creates an extension registry from a map of extension lists.
         */
        public fun fromLists(extensions: Map<KClass<out RealmExtension>, List<RealmExtension>>): ExtensionRegistry {
            return ExtensionRegistry(extensions)
        }
    }
}
