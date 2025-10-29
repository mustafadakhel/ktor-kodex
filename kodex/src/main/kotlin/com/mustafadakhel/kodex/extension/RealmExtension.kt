package com.mustafadakhel.kodex.extension

import org.jetbrains.exposed.sql.Table
import kotlin.reflect.KClass

/** Base interface for realm extensions. */
public interface RealmExtension {
    /** Execution priority. Lower values run first. */
    public val priority: Int get() = 100
}

/** Extension that requires database tables. */
public interface PersistentExtension : RealmExtension {
    /** Returns database tables required by this extension. */
    public fun tables(): List<Table>
}

/** Registry for looking up extensions by type. */
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

    public fun getTables(): List<Table> {
        return extensions.values
            .flatten()
            .filterIsInstance<PersistentExtension>()
            .flatMap { it.tables() }
            .distinct()
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
