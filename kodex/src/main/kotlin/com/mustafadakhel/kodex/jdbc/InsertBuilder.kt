package com.mustafadakhel.kodex.jdbc

import java.sql.Connection
import java.sql.PreparedStatement

public class InsertBuilder(private val table: TableDef, private val conn: Connection) {

    private val columnValues = mutableListOf<Pair<Column<*>, Any?>>()

    public operator fun <T> set(column: Column<T>, value: T) {
        columnValues.add(column to value)
    }

    public fun execute() {
        val sql = buildInsertSql()
        conn.prepareStatement(sql).use { ps ->
            bindParams(ps)
            ps.executeUpdate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    public fun <K> executeAndReturnKey(keyColumn: Column<K>): K {
        val sql = buildInsertSql()
        conn.prepareStatement(sql, arrayOf(keyColumn.name)).use { ps ->
            bindParams(ps)
            ps.executeUpdate()
            ps.generatedKeys.use { rs ->
                check(rs.next()) { "No generated key returned for ${table.tableName}" }
                return keyColumn.sqlType.get(rs, keyColumn.name) as K
            }
        }
    }

    internal fun getColumnValues(): List<Pair<Column<*>, Any?>> = columnValues.toList()

    internal fun buildInsertSql(): String {
        val cols = columnValues.joinToString(", ") { it.first.name }
        val placeholders = columnValues.joinToString(", ") { "?" }
        return "INSERT INTO ${table.tableName} ($cols) VALUES ($placeholders)"
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindParams(ps: PreparedStatement) {
        columnValues.forEachIndexed { index, (col, value) ->
            (col.sqlType as SqlType<Any?>).set(ps, index + 1, value)
        }
    }
}
