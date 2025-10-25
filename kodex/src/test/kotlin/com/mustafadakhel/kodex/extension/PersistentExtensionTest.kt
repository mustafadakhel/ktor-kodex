package com.mustafadakhel.kodex.extension

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Table

private object TestTable1 : Table("test_table_1") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}

private object TestTable2 : Table("test_table_2") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}

private object TestTable3 : Table("test_table_3") {
    val id = integer("id")
    override val primaryKey = PrimaryKey(id)
}

class PersistentExtensionTest : DescribeSpec({

    describe("PersistentExtension") {
        it("should return tables from single persistent extension") {
            val extension = object : PersistentExtension, UserLifecycleHooks {
                override fun tables() = listOf(TestTable1, TestTable2)
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to extension))
            val tables = registry.getTables()

            tables shouldContainExactly listOf(TestTable1, TestTable2)
        }

        it("should collect tables from multiple persistent extensions") {
            val extension1 = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 1
                override fun tables() = listOf(TestTable1)
            }

            val extension2 = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 2
                override fun tables() = listOf(TestTable2, TestTable3)
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(extension1, extension2))
            )
            val tables = registry.getTables()

            tables shouldContainExactly listOf(TestTable1, TestTable2, TestTable3)
        }

        it("should deduplicate tables when multiple extensions return same table") {
            val extension1 = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 1
                override fun tables() = listOf(TestTable1, TestTable2)
            }

            val extension2 = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 2
                override fun tables() = listOf(TestTable2, TestTable3)
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(extension1, extension2))
            )
            val tables = registry.getTables()

            tables shouldContainExactly listOf(TestTable1, TestTable2, TestTable3)
        }

        it("should return empty list when no persistent extensions registered") {
            val extension = object : UserLifecycleHooks {
                // Non-persistent extension
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to extension))
            val tables = registry.getTables()

            tables shouldBe emptyList()
        }

        it("should mix persistent and non-persistent extensions") {
            val persistentExt = object : PersistentExtension, UserLifecycleHooks {
                override val priority = 1
                override fun tables() = listOf(TestTable1)
            }

            val nonPersistentExt = object : UserLifecycleHooks {
                override val priority = 2
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(persistentExt, nonPersistentExt))
            )
            val tables = registry.getTables()

            tables shouldContainExactly listOf(TestTable1)
        }

        it("should return empty list from empty registry") {
            val registry = ExtensionRegistry.empty()
            val tables = registry.getTables()

            tables shouldBe emptyList()
        }
    }
})
