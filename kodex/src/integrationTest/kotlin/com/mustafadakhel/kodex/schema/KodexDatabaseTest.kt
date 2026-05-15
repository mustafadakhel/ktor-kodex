package com.mustafadakhel.kodex.schema

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import java.util.UUID

private class TestExtensionSchema(private val schemaTables: List<Table>) : ExtensionSchema {
    override fun tables(): List<Table> = schemaTables
}

private class AnotherExtensionSchema : ExtensionSchema {
    override fun tables(): List<Table> = emptyList()
}

class KodexDatabaseTest : FunSpec({

    context("validateSchema") {

        test("throws IllegalStateException when tables are missing") {
            val database = Database.connect(
                "jdbc:h2:mem:validate_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            val core = CoreSchema("test_")
            val db = KodexDatabase(database, core)

            val exception = shouldThrow<IllegalStateException> {
                db.validateSchema()
            }

            exception.message shouldContain "Required Kodex tables missing"
        }
    }

    context("generateDDL") {

        test("returns non-empty list of CREATE TABLE statements") {
            val database = Database.connect(
                "jdbc:h2:mem:ddl_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            val core = CoreSchema("test_")
            val db = KodexDatabase(database, core)

            val ddl = db.generateDDL()

            ddl.shouldNotBeEmpty()
            val createTableStatements = ddl.filter { it.contains("CREATE TABLE") }
            createTableStatements.shouldNotBeEmpty()
        }
    }

    context("schema and schemaOrNull") {

        test("schema returns registered extension schema") {
            val database = Database.connect(
                "jdbc:h2:mem:schema_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            val core = CoreSchema("test_")
            val testSchema = TestExtensionSchema(emptyList())
            val db = KodexDatabase(
                database = database,
                core = core,
                extensionSchemas = mapOf(TestExtensionSchema::class to testSchema)
            )

            val result = db.schema<TestExtensionSchema>()

            result shouldBe testSchema
        }

        test("schemaOrNull returns null for unregistered extension schema") {
            val database = Database.connect(
                "jdbc:h2:mem:schema_null_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            val core = CoreSchema("test_")
            val db = KodexDatabase(database, core)

            val result = db.schemaOrNull<AnotherExtensionSchema>()

            result shouldBe null
        }

        test("schema throws for unregistered extension schema") {
            val database = Database.connect(
                "jdbc:h2:mem:schema_throw_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )
            val core = CoreSchema("test_")
            val db = KodexDatabase(database, core)

            val exception = shouldThrow<IllegalStateException> {
                db.schema<AnotherExtensionSchema>()
            }

            exception.message shouldContain "AnotherExtensionSchema not registered"
        }
    }

    context("close") {

        test("closes datasource when ownsDataSource is true") {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:close_owned_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
                driverClassName = "org.h2.Driver"
                maximumPoolSize = 2
                isAutoCommit = false
            }
            val dataSource = HikariDataSource(hikariConfig)
            val database = Database.connect(dataSource)
            val core = CoreSchema("test_")
            val db = KodexDatabase(
                database = database,
                core = core,
                ownsDataSource = true,
                dataSource = dataSource
            )

            db.close()

            dataSource.isClosed shouldBe true
        }

        test("does not close datasource when ownsDataSource is false") {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:close_unowned_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
                driverClassName = "org.h2.Driver"
                maximumPoolSize = 2
                isAutoCommit = false
            }
            val dataSource = HikariDataSource(hikariConfig)
            val database = Database.connect(dataSource)
            val core = CoreSchema("test_")
            val db = KodexDatabase(
                database = database,
                core = core,
                ownsDataSource = false,
                dataSource = dataSource
            )

            db.close()

            dataSource.isClosed shouldBe false
            dataSource.close()
        }
    }
})
