package com.mustafadakhel.kodex.schema

import com.mustafadakhel.kodex.jdbc.ConnectionScope
import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource
import kotlin.reflect.KClass

public class KodexDatabase(
    public val dataSource: DataSource,
    public val dialect: DatabaseDialect,
    public val core: CoreSchema,
    @PublishedApi internal val extensionSchemas: Map<KClass<out ExtensionSchema>, ExtensionSchema> = emptyMap(),
    internal val ownsDataSource: Boolean = false,
) {
    public fun <R> transaction(block: ConnectionScope.() -> R): R {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = ConnectionScope(conn, dialect).block()
                conn.commit()
                return result
            } catch (e: Throwable) {
                try { conn.rollback() } catch (re: Throwable) { e.addSuppressed(re) }
                throw e
            }
        }
    }

    public suspend fun <R> suspendTransaction(block: suspend ConnectionScope.() -> R): R =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val result = ConnectionScope(conn, dialect).block()
                    conn.commit()
                    result
                } catch (e: Throwable) {
                    try { conn.rollback() } catch (re: Throwable) { e.addSuppressed(re) }
                    throw e
                }
            }
        }

    public fun createSchema() {
        transaction {
            val statements = coreDDL() + extensionSchemas.values.flatMap { it.ddl(dialect) }
            conn.createStatement().use { stmt ->
                for (sql in statements) {
                    stmt.execute(sql)
                }
            }
        }
    }

    public fun validateSchema() {
        transaction {
            val rs = conn.metaData.getTables(null, null, null, arrayOf("TABLE"))
            val existing = mutableSetOf<String>()
            while (rs.next()) {
                existing.add(rs.getString("TABLE_NAME").uppercase())
            }

            val expected = coreTableNames() + extensionSchemas.values.flatMap { it.tableNames() }
            val missing = expected.filter { it.uppercase() !in existing }
            if (missing.isNotEmpty()) {
                error(
                    "Required Kodex tables missing: ${missing.joinToString()}. " +
                        "Run your migration tool first, or set autoCreateTables = true. " +
                        "Use generateDDL() to get the required SQL."
                )
            }
        }
    }

    public fun generateDDL(): List<String> =
        coreDDL() + extensionSchemas.values.flatMap { it.ddl(dialect) }

    public inline fun <reified T : ExtensionSchema> schema(): T =
        extensionSchemas[T::class] as? T
            ?: error("${T::class.simpleName} not registered. Is the extension installed?")

    public inline fun <reified T : ExtensionSchema> schemaOrNull(): T? =
        extensionSchemas[T::class] as? T

    public fun close() {
        if (ownsDataSource) (dataSource as? HikariDataSource)?.close()
    }

    private fun coreTableNames(): List<String> =
        core.tables().map { it.tableName }

    private fun coreDDL(): List<String> =
        core.tables().flatMap { table ->
            listOf(table.createTableDDL(dialect)) + table.createIndexDDL(dialect)
        }
}
