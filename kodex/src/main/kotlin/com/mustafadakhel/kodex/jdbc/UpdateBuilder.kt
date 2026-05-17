@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.jdbc

import java.sql.Connection

@InternalKodexApi
public class UpdateBuilder(private val table: TableDef, private val conn: Connection) {

    private sealed interface SetEntry {
        val column: Column<*>

        data class Value(override val column: Column<*>, val value: Any?) : SetEntry
        data class Expression(override val column: Column<*>, val sql: String, val params: List<BoundParam>) : SetEntry
    }

    private val entries = mutableListOf<SetEntry>()
    private var whereClause: WhereClause? = null

    public operator fun <T> set(column: Column<T>, value: T) {
        entries.add(SetEntry.Value(column, value))
    }

    /**
     * Sets a column using a raw SQL expression. Useful for atomic operations like:
     * ```
     * setExpression(table.attempts, "${table.attempts.name} + 1")
     * ```
     */
    public fun setExpression(column: Column<*>, expression: String, vararg params: BoundParam) {
        entries.add(SetEntry.Expression(column, expression, params.toList()))
    }

    public fun where(block: () -> WhereClause): UpdateBuilder {
        whereClause = block()
        return this
    }

    @Suppress("UNCHECKED_CAST")
    public fun execute(): Int {
        requireNotNull(whereClause) {
            "UpdateBuilder.execute() requires a WHERE clause. Use executeAll() for intentional bulk updates."
        }
        return executeInternal()
    }

    /** Execute without WHERE clause — updates all rows in the table. */
    public fun executeAll(): Int = executeInternal()

    @Suppress("UNCHECKED_CAST")
    private fun executeInternal(): Int {
        val setCols = entries.joinToString(", ") { entry ->
            when (entry) {
                is SetEntry.Value -> "${entry.column.name} = ?"
                is SetEntry.Expression -> "${entry.column.name} = ${entry.sql}"
            }
        }
        val sql = buildString {
            append("UPDATE ${table.tableName} SET $setCols")
            whereClause?.let { append(" WHERE ${it.sql}") }
        }
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            for (entry in entries) {
                when (entry) {
                    is SetEntry.Value -> (entry.column.sqlType as SqlType<Any?>).set(ps, idx++, entry.value)
                    is SetEntry.Expression -> {
                        for (param in entry.params) {
                            param.bind(ps, idx++)
                        }
                    }
                }
            }
            whereClause?.params?.let { idx = it.bindTo(ps, idx) }
            ps.executeUpdate()
        }
    }
}
