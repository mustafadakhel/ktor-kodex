package com.mustafadakhel.kodex.extension

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Table

// Test extension interfaces
private interface TestExtension : RealmExtension
private interface AnotherExtension : RealmExtension

// Concrete test extensions
private class TestExtensionImpl(override val priority: Int = 100) : TestExtension
private class AnotherExtensionImpl : AnotherExtension

// Persistent extension for table testing
private class PersistentTestExtension : PersistentExtension {
    companion object {
        val TestTable = object : Table("test_table") {}
        val AnotherTestTable = object : Table("another_test_table") {}
    }

    override fun tables(): List<Table> = listOf(TestTable, AnotherTestTable)
}

class ExtensionRegistryTest : DescribeSpec({

    describe("ExtensionRegistry") {
        describe("empty") {
            it("should create empty registry") {
                val registry = ExtensionRegistry.empty()

                registry.getAllOfType(TestExtension::class).shouldBeEmpty()
                registry.get(TestExtension::class).shouldBeNull()
                registry.has(TestExtension::class) shouldBe false
            }

            it("should return empty list for any extension type") {
                val registry = ExtensionRegistry.empty()

                registry.getAllOfType(UserLifecycleHooks::class).shouldBeEmpty()
                registry.getAllOfType(EventSubscriberProvider::class).shouldBeEmpty()
            }

            it("should return empty tables list") {
                val registry = ExtensionRegistry.empty()
                registry.getTables().shouldBeEmpty()
            }
        }

        describe("from") {
            it("should create registry with single extension") {
                val extension = TestExtensionImpl()
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to extension)
                )

                val retrieved = registry.get(TestExtension::class)
                retrieved.shouldNotBeNull()
                retrieved shouldBe extension
            }

            it("should support multiple extension types") {
                val testExt = TestExtensionImpl()
                val anotherExt = AnotherExtensionImpl()

                val registry = ExtensionRegistry.from(
                    mapOf(
                        TestExtension::class to testExt,
                        AnotherExtension::class to anotherExt
                    )
                )

                registry.get(TestExtension::class) shouldBe testExt
                registry.get(AnotherExtension::class) shouldBe anotherExt
            }

            it("should convert single extension to list") {
                val extension = TestExtensionImpl()
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to extension)
                )

                val allExtensions = registry.getAllOfType(TestExtension::class)
                allExtensions.size shouldBe 1
                allExtensions[0] shouldBe extension
            }
        }

        describe("fromLists") {
            it("should support multiple extensions of same type") {
                val ext1 = TestExtensionImpl(priority = 10)
                val ext2 = TestExtensionImpl(priority = 20)
                val ext3 = TestExtensionImpl(priority = 30)

                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to listOf(ext1, ext2, ext3))
                )

                val allExtensions = registry.getAllOfType(TestExtension::class)
                allExtensions.size shouldBe 3
                allExtensions shouldContainExactly listOf(ext1, ext2, ext3)
            }

            it("should maintain registration order") {
                val ext1 = TestExtensionImpl()
                val ext2 = TestExtensionImpl()
                val ext3 = TestExtensionImpl()

                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to listOf(ext1, ext2, ext3))
                )

                val allExtensions = registry.getAllOfType(TestExtension::class)
                allExtensions[0] shouldBe ext1
                allExtensions[1] shouldBe ext2
                allExtensions[2] shouldBe ext3
            }

            it("should support empty lists") {
                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to emptyList())
                )

                registry.getAllOfType(TestExtension::class).shouldBeEmpty()
                registry.has(TestExtension::class) shouldBe false
            }
        }

        describe("getAllOfType") {
            it("should return all extensions of requested type") {
                val ext1 = TestExtensionImpl()
                val ext2 = TestExtensionImpl()

                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to listOf(ext1, ext2))
                )

                val allExtensions = registry.getAllOfType(TestExtension::class)
                allExtensions shouldContainExactly listOf(ext1, ext2)
            }

            it("should return empty list for unregistered type") {
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to TestExtensionImpl())
                )

                registry.getAllOfType(AnotherExtension::class).shouldBeEmpty()
            }

            it("should return empty list for empty registry") {
                val registry = ExtensionRegistry.empty()
                registry.getAllOfType(TestExtension::class).shouldBeEmpty()
            }
        }

        describe("get") {
            it("should return first extension of type") {
                val ext1 = TestExtensionImpl(priority = 10)
                val ext2 = TestExtensionImpl(priority = 20)

                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to listOf(ext1, ext2))
                )

                registry.get(TestExtension::class) shouldBe ext1
            }

            it("should return null for unregistered type") {
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to TestExtensionImpl())
                )

                registry.get(AnotherExtension::class).shouldBeNull()
            }

            it("should return null for empty registry") {
                val registry = ExtensionRegistry.empty()
                registry.get(TestExtension::class).shouldBeNull()
            }
        }

        describe("has") {
            it("should return true when extension is registered") {
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to TestExtensionImpl())
                )

                registry.has(TestExtension::class) shouldBe true
            }

            it("should return false for unregistered type") {
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to TestExtensionImpl())
                )

                registry.has(AnotherExtension::class) shouldBe false
            }

            it("should return false for empty list") {
                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to emptyList())
                )

                registry.has(TestExtension::class) shouldBe false
            }

            it("should return true when multiple extensions registered") {
                val registry = ExtensionRegistry.fromLists(
                    mapOf(TestExtension::class to listOf(TestExtensionImpl(), TestExtensionImpl()))
                )

                registry.has(TestExtension::class) shouldBe true
            }
        }

        describe("getTables") {
            it("should collect tables from persistent extensions") {
                val persistentExt = PersistentTestExtension()
                val registry = ExtensionRegistry.from(
                    mapOf(PersistentExtension::class to persistentExt)
                )

                val tables = registry.getTables()
                tables.size shouldBe 2
                tables shouldContainExactly listOf(
                    PersistentTestExtension.TestTable,
                    PersistentTestExtension.AnotherTestTable
                )
            }

            it("should return empty list when no persistent extensions") {
                val registry = ExtensionRegistry.from(
                    mapOf(TestExtension::class to TestExtensionImpl())
                )

                registry.getTables().shouldBeEmpty()
            }

            it("should return empty list for empty registry") {
                val registry = ExtensionRegistry.empty()
                registry.getTables().shouldBeEmpty()
            }

            it("should deduplicate tables from multiple extensions") {
                val sharedTable = object : Table("shared_table") {}

                val ext1 = object : PersistentExtension {
                    override fun tables() = listOf(sharedTable)
                }

                val ext2 = object : PersistentExtension {
                    override fun tables() = listOf(sharedTable)
                }

                val registry = ExtensionRegistry.fromLists(
                    mapOf(PersistentExtension::class to listOf(ext1, ext2))
                )

                val tables = registry.getTables()
                tables.size shouldBe 1
                tables[0] shouldBe sharedTable
            }

            it("should flatten tables from multiple persistent extensions") {
                val table1 = object : Table("table1") {}
                val table2 = object : Table("table2") {}

                val ext1 = object : PersistentExtension {
                    override fun tables() = listOf(table1)
                }

                val ext2 = object : PersistentExtension {
                    override fun tables() = listOf(table2)
                }

                val registry = ExtensionRegistry.fromLists(
                    mapOf(PersistentExtension::class to listOf(ext1, ext2))
                )

                val tables = registry.getTables()
                tables.size shouldBe 2
                tables shouldContainExactly listOf(table1, table2)
            }
        }

        describe("priority") {
            it("should use default priority 100") {
                val extension = object : RealmExtension {}
                extension.priority shouldBe 100
            }

            it("should allow custom priority") {
                val extension = object : RealmExtension {
                    override val priority = 50
                }
                extension.priority shouldBe 50
            }
        }
    }

    describe("ExtensionConfig") {
        class TestExtensionConfig : ExtensionConfig() {
            var value: String = "default"

            override fun build(context: ExtensionContext): RealmExtension {
                return object : RealmExtension {
                    val configValue = value
                }
            }
        }

        it("should build extension with context") {
            val config = TestExtensionConfig()
            config.value = "custom"

            val context = extensionContext(
                realm = com.mustafadakhel.kodex.model.Realm("test"),
                timeZone = kotlinx.datetime.TimeZone.UTC
            )

            val extension = config.build(context)
            extension.shouldNotBeNull()
        }

        it("should allow configuration before build") {
            val config = TestExtensionConfig()
            config.value = "configured"

            val context = extensionContext(
                realm = com.mustafadakhel.kodex.model.Realm("test"),
                timeZone = kotlinx.datetime.TimeZone.UTC
            )

            val extension = config.build(context) as? RealmExtension
            extension.shouldNotBeNull()
        }

        it("should use context in build") {
            class ContextAwareConfig : ExtensionConfig() {
                override fun build(context: ExtensionContext): RealmExtension {
                    return object : RealmExtension {
                        val realmOwner = context.realm.owner
                        val timeZone = context.timeZone
                    }
                }
            }

            val config = ContextAwareConfig()
            val context = extensionContext(
                realm = com.mustafadakhel.kodex.model.Realm("my-realm"),
                timeZone = kotlinx.datetime.TimeZone.of("America/New_York")
            )

            val extension = config.build(context)
            extension.shouldNotBeNull()
        }
    }
})
