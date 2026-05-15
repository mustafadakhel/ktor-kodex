package com.mustafadakhel.kodex.jdbc

import java.sql.Connection

public class DeleteBuilder(
    private val table: TableDef,
    private val conn: Connection,
    private val dialect: DatabaseDialect,
) {

    private var whereClause: WhereClause? = null
    private var limitValue: Int? = null

    public fun where(block: () -> WhereClause): DeleteBuilder {
        whereClause = block()
        return this
    }

    public fun limit(n: Int): DeleteBuilder {
        limitValue = n
        return this
    }

    public fun execute(): Int {
        val where = whereClause
        val limit = limitValue

        require(limit == null || where != null) {
            "DeleteBuilder.limit() requires a where clause. Use executeAll() to delete all rows."
        }

        val sql = when {
            where == null -> "DELETE FROM ${table.tableName}"
            limit == null -> "DELETE FROM ${table.tableName} WHERE ${where.sql}"
            else -> buildLimitedDeleteSql(where, limit)
        }

        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            if (where != null) {
                idx = where.params.bindTo(ps, idx)
            }
            if (limit != null) {
                ps.setInt(idx, limit)
            }
            ps.executeUpdate()
        }
    }

    public fun executeAll(): Int =
        conn.prepareStatement("DELETE FROM ${table.tableName}").use { it.executeUpdate() }

    private fun buildLimitedDeleteSql(where: WhereClause, @Suppress("UNUSED_PARAMETER") limit: Int): String =
        when (dialect) {
            DatabaseDialect.H2 -> "DELETE FROM ${table.tableName} WHERE ${where.sql} LIMIT ?"
            DatabaseDialect.POSTGRESQL -> {
                val pkCol = table.primaryKey.columns.first().name
                "DELETE FROM ${table.tableName} WHERE $pkCol IN " +
                    "(SELECT $pkCol FROM ${table.tableName} WHERE ${where.sql} LIMIT ?)"
            }
        }
}
