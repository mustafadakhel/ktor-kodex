package com.mustafadakhel.kodex.extension

import kotlin.reflect.KClass

public interface RealmExtension {
    /** Execution priority. Lower values run first. */
    public val priority: Int get() = 100
}

/**
 * Interface for extensions that own resources requiring cleanup on application shutdown.
 * Extensions implementing this will have [shutdown] called during [ApplicationStopping],
 * before the database connection is closed.
 */
public interface Shutdownable {
    public fun shutdown()
}

public class ExtensionRegistry internal constructor(
    private val extensions: Map<KClass<out RealmExtension>, List<RealmExtension>>
) {
    public fun <T : RealmExtension> getAllOfType(extensionClass: KClass<T>): List<T> {
        @Suppress("UNCHECKED_CAST")
        return extensions[extensionClass] as? List<T> ?: emptyList()
    }

    public fun <T : RealmExtension> get(extensionClass: KClass<T>): T? {
        return getAllOfType(extensionClass).firstOrNull()
    }

    public fun <T : RealmExtension> has(extensionClass: KClass<T>): Boolean {
        return extensions.containsKey(extensionClass) && extensions[extensionClass]?.isNotEmpty() == true
    }

    internal fun shutdownAll() {
        extensions.values.flatten()
            .distinct()
            .filterIsInstance<Shutdownable>()
            .forEach { it.shutdown() }
    }

    public companion object {
        public fun empty(): ExtensionRegistry = ExtensionRegistry(emptyMap())

        public fun from(extensions: Map<KClass<out RealmExtension>, RealmExtension>): ExtensionRegistry {
            val extensionLists = extensions.mapValues { (_, extension) -> listOf(extension) }
            return ExtensionRegistry(extensionLists)
        }

        public fun fromLists(extensions: Map<KClass<out RealmExtension>, List<RealmExtension>>): ExtensionRegistry {
            return ExtensionRegistry(extensions)
        }
    }
}
