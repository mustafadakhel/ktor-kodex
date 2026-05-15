package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class TestStubExtensionSchema(private val names: List<String>) : ExtensionSchema {
    override fun ddl(dialect: DatabaseDialect): List<String> = emptyList()
    override fun tableNames(): List<String> = names
}

class PersistentExtensionTest : DescribeSpec({

    val core = CoreSchema("test_")

    describe("PersistentExtension") {
        it("should return table names from single persistent extension") {
            val extension = object : PersistentExtension, UserLifecycleHooks {
                override fun createSchema(core: CoreSchema): ExtensionSchema =
                    TestStubExtensionSchema(listOf("test_table_1", "test_table_2"))
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to extension))
            val names = registry.collectSchemas(core).values.flatMap { it.tableNames() }

            names shouldContainExactly listOf("test_table_1", "test_table_2")
        }

        it("should collect table names from multiple persistent extensions") {
            val extension1 = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 1
                override fun createSchema(core: CoreSchema): ExtensionSchema =
                    object : ExtensionSchema {
                        override fun ddl(dialect: DatabaseDialect) = emptyList<String>()
                        override fun tableNames() = listOf("test_table_1")
                    }
            }

            val extension2 = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 2
                override fun createSchema(core: CoreSchema): ExtensionSchema =
                    object : ExtensionSchema {
                        override fun ddl(dialect: DatabaseDialect) = emptyList<String>()
                        override fun tableNames() = listOf("test_table_2", "test_table_3")
                    }
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(extension1, extension2))
            )
            val names = registry.collectSchemas(core).values.flatMap { it.tableNames() }

            names shouldContainExactly listOf("test_table_1", "test_table_2", "test_table_3")
        }

        it("should return empty list when no persistent extensions registered") {
            val extension = object : UserLifecycleHooks {
                // Non-persistent extension
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to extension))
            val names = registry.collectSchemas(core).values.flatMap { it.tableNames() }

            names shouldBe emptyList()
        }

        it("should mix persistent and non-persistent extensions") {
            val persistentExt = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 1
                override fun createSchema(core: CoreSchema): ExtensionSchema =
                    TestStubExtensionSchema(listOf("test_table_1"))
            }

            val nonPersistentExt = object : UserLifecycleHooks {
                override val priority = 2
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(persistentExt, nonPersistentExt))
            )
            val names = registry.collectSchemas(core).values.flatMap { it.tableNames() }

            names shouldContainExactly listOf("test_table_1")
        }

        it("should return empty list from empty registry") {
            val registry = ExtensionRegistry.empty()
            val names = registry.collectSchemas(core).values.flatMap { it.tableNames() }

            names shouldBe emptyList()
        }
    }
})
