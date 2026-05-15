package com.mustafadakhel.kodex.jdbc

import java.sql.Connection
import java.sql.ResultSet

public data class JoinClause(
    val table: TableDef,
    val on: WhereClause,
)

public class SelectBuilder(
    private val table: TableDef,
    private val conn: Connection,
    private val dialect: DatabaseDialect,
) {
    private var whereClause: WhereClause? = null
    private val orderByList = mutableListOf<Pair<Column<*>, SortOrder>>()
    private var limitValue: Int? = null
    private var offsetValue: Long? = null
    private var forUpdate: Boolean = false
    private val joinClauses = mutableListOf<JoinClause>()
    private var selectColumns: List<Column<*>>? = null

    public fun where(block: () -> WhereClause): SelectBuilder {
        whereClause = block()
        return this
    }

    public fun orderBy(column: Column<*>, order: SortOrder = SortOrder.ASC): SelectBuilder {
        orderByList.add(column to order)
        return this
    }

    public fun limit(n: Int): SelectBuilder {
        limitValue = n
        return this
    }

    public fun offset(n: Long): SelectBuilder {
        offsetValue = n
        return this
    }

    public fun forUpdate(): SelectBuilder {
        forUpdate = true
        return this
    }

    public fun innerJoin(other: TableDef, on: () -> WhereClause): SelectBuilder {
        joinClauses.add(JoinClause(other, on()))
        return this
    }

    public fun columns(vararg cols: Column<*>): SelectBuilder {
        selectColumns = cols.toList()
        return this
    }

    public fun <T> map(mapper: (Row) -> T): List<T> {
        val results = mutableListOf<T>()
        executePrepared(buildSql(), collectParams()) { rs ->
            val row = Row(rs)
            while (rs.next()) {
                results.add(mapper(row))
            }
        }
        return results
    }

    public fun <T> firstOrNull(mapper: (Row) -> T): T? {
        val sql = buildSql(limitOverride = 1)
        var result: T? = null
        executePrepared(sql, collectParams()) { rs ->
            val row = Row(rs)
            if (rs.next()) result = mapper(row)
        }
        return result
    }

    public fun <T> singleOrNull(mapper: (Row) -> T): T? {
        val sql = buildSql(limitOverride = 2)
        val results = mutableListOf<T>()
        executePrepared(sql, collectParams()) { rs ->
            val row = Row(rs)
            while (rs.next()) results.add(mapper(row))
        }
        check(results.size <= 1) { "Expected at most 1 result but got ${results.size}" }
        return results.firstOrNull()
    }

    public fun any(): Boolean {
        val sql = buildSql(countMode = false, existsMode = true)
        var found = false
        executePrepared(sql, collectParams()) { rs -> found = rs.next() }
        return found
    }

    public fun count(): Long {
        val sql = buildSql(countMode = true)
        var result = 0L
        executePrepared(sql, collectParams()) { rs ->
            rs.next()
            result = rs.getLong(1)
        }
        return result
    }

    private fun executePrepared(sql: String, params: List<BoundParam>, block: (ResultSet) -> Unit) {
        conn.prepareStatement(sql).use { ps ->
            params.bindTo(ps)
            ps.executeQuery().use(block)
        }
    }

    private fun buildSql(
        countMode: Boolean = false,
        existsMode: Boolean = false,
        limitOverride: Int? = null,
    ): String = buildString {
        if (countMode) {
            append("SELECT COUNT(*) FROM ${table.tableName}")
        } else if (existsMode) {
            append("SELECT 1 FROM ${table.tableName}")
        } else {
            val cols = selectColumns?.joinToString(", ") { it.qualifiedName }
                ?: "${table.tableName}.*" + joinClauses.joinToString("") { ", ${it.table.tableName}.*" }
            append("SELECT $cols FROM ${table.tableName}")
        }

        for (join in joinClauses) {
            append(" INNER JOIN ${join.table.tableName} ON ${join.on.sql}")
        }

        whereClause?.let { append(" WHERE ${it.sql}") }

        if (!countMode) {
            if (!existsMode && orderByList.isNotEmpty()) {
                append(" ORDER BY ")
                append(orderByList.joinToString(", ") { "${it.first.qualifiedName} ${it.second.name}" })
            }
            val effectiveLimit = limitOverride ?: limitValue
            effectiveLimit?.let { append(" LIMIT $it") }
            if (!existsMode) {
                offsetValue?.let { append(" OFFSET $it") }
                if (forUpdate) append(" FOR UPDATE")
            }
        }
    }

    private fun collectParams(): List<BoundParam> = buildList {
        for (join in joinClauses) addAll(join.on.params)
        whereClause?.let { addAll(it.params) }
    }
}
