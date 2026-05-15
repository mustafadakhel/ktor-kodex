package com.mustafadakhel.kodex.schema

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlin.reflect.KClass

public class KodexDatabase(
    internal val database: Database,
    public val core: CoreSchema,
    @PublishedApi internal val extensionSchemas: Map<KClass<out ExtensionSchema>, ExtensionSchema> = emptyMap(),
    internal val ownsDataSource: Boolean = false,
    internal val dataSource: HikariDataSource? = null
) {
    public fun <R> transaction(statement: Transaction.() -> R): R =
        org.jetbrains.exposed.sql.transactions.transaction(db = database, statement = statement)

    public suspend fun <R> suspendTransaction(statement: suspend Transaction.() -> R): R =
        newSuspendedTransaction(Dispatchers.IO, db = database, statement = statement)

    public fun createSchema() {
        transaction {
            SchemaUtils.create(*allTables.toTypedArray())
        }
    }

    public fun validateSchema() {
        transaction {
            val missing = allTables.filter { !it.exists() }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString { it.tableName }
                error(
                    "Required Kodex tables missing: $names. " +
                    "Run your migration tool first, or set autoCreateTables = true. " +
                    "Use generateDDL() to get the required SQL."
                )
            }
        }
    }

    public fun generateDDL(): List<String> = transaction {
        allTables.flatMap { SchemaUtils.createStatements(it) }
    }

    public inline fun <reified T : ExtensionSchema> schema(): T =
        extensionSchemas[T::class] as? T
            ?: error("${T::class.simpleName} not registered. Is the extension installed?")

    public inline fun <reified T : ExtensionSchema> schemaOrNull(): T? =
        extensionSchemas[T::class] as? T

    public fun close() {
        if (ownsDataSource) dataSource?.close()
    }

    private val allTables: List<Table> =
        core.tables() + extensionSchemas.values.flatMap { it.tables() }
}
