package com.mustafadakhel.kodex.jdbc

import java.sql.Connection

public class UpdateBuilder(private val table: TableDef, private val conn: Connection) {

    private val columnValues = mutableListOf<Pair<Column<*>, Any?>>()
    private var whereClause: WhereClause? = null

    public operator fun <T> set(column: Column<T>, value: T) {
        columnValues.add(column to value)
    }

    public fun where(block: () -> WhereClause): UpdateBuilder {
        whereClause = block()
        return this
    }

    @Suppress("UNCHECKED_CAST")
    public fun execute(): Int {
        val setCols = columnValues.joinToString(", ") { "${it.first.name} = ?" }
        val sql = buildString {
            append("UPDATE ${table.tableName} SET $setCols")
            whereClause?.let { append(" WHERE ${it.sql}") }
        }
        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            for ((col, value) in columnValues) {
                (col.sqlType as SqlType<Any?>).set(ps, idx++, value)
            }
            whereClause?.params?.let { idx = it.bindTo(ps, idx) }
            ps.executeUpdate()
        }
    }
}
