@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.jdbc

import java.sql.Connection

@InternalKodexApi
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
        val where = requireNotNull(whereClause) {
            "DeleteBuilder.execute() requires a WHERE clause. Use executeAll() to delete all rows."
        }
        val limit = limitValue

        val sql = when {
            limit == null -> "DELETE FROM ${table.tableName} WHERE ${where.sql}"
            else -> buildLimitedDeleteSql(where, limit)
        }

        return conn.prepareStatement(sql).use { ps ->
            var idx = 1
            idx = where.params.bindTo(ps, idx)
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
