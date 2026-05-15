package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlin.reflect.KClass

public interface RealmExtension {
    /** Execution priority. Lower values run first. */
    public val priority: Int get() = 100
}

public interface PersistentExtension : RealmExtension {
    public fun createSchema(core: CoreSchema): ExtensionSchema
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

    public fun collectSchemas(core: CoreSchema): Map<KClass<out ExtensionSchema>, ExtensionSchema> {
        return extensions.values
            .flatten()
            .filterIsInstance<PersistentExtension>()
            .map { it.createSchema(core) }
            .associateBy { it::class }
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
