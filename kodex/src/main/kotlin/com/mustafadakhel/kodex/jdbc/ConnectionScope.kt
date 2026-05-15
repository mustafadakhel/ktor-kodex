package com.mustafadakhel.kodex.jdbc

import java.sql.Connection

internal class ConnectionScope(
    @PublishedApi internal val conn: Connection,
    public val dialect: DatabaseDialect,
) {
    public fun select(table: TableDef): SelectBuilder = SelectBuilder(table, conn, dialect)

    public fun insertInto(table: TableDef, block: InsertBuilder.() -> Unit) {
        InsertBuilder(table, conn).apply(block).execute()
    }

    public fun <K> insertReturningKey(table: TableDef, keyColumn: Column<K>, block: InsertBuilder.() -> Unit): K =
        InsertBuilder(table, conn).apply(block).executeAndReturnKey(keyColumn)

    public fun update(table: TableDef, block: UpdateBuilder.() -> Unit): Int =
        UpdateBuilder(table, conn).apply(block).execute()

    public fun deleteFrom(table: TableDef): DeleteBuilder = DeleteBuilder(table, conn, dialect)

    @Suppress("UNCHECKED_CAST")
    public fun <T> batchInsert(table: TableDef, items: List<T>, block: InsertBuilder.(T) -> Unit) {
        if (items.isEmpty()) return
        val first = InsertBuilder(table, conn).apply { block(items.first()) }
        val columnValues = first.getColumnValues()
        val cols = columnValues.map { it.first }
        val colNames = cols.joinToString(", ") { it.name }
        val placeholders = cols.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${table.tableName} ($colNames) VALUES ($placeholders)"

        conn.prepareStatement(sql).use { ps ->
            for (item in items) {
                val builder = InsertBuilder(table, conn).apply { block(item) }
                val values = builder.getColumnValues()
                values.forEachIndexed { index, (col, value) ->
                    (col.sqlType as SqlType<Any?>).set(ps, index + 1, value)
                }
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Suppress("UNCHECKED_CAST")
    public fun upsert(table: TableDef, conflictColumns: List<Column<*>>, block: InsertBuilder.() -> Unit): Int {
        val builder = InsertBuilder(table, conn).apply(block)
        val columnValues = builder.getColumnValues()
        val allCols = columnValues.map { it.first }
        val conflictNames = conflictColumns.map { it.name }.toSet()
        val updateCols = allCols.filter { it.name !in conflictNames }

        val sql = when (dialect) {
            DatabaseDialect.POSTGRESQL -> {
                val colNames = allCols.joinToString(", ") { it.name }
                val placeholders = allCols.joinToString(", ") { "?" }
                val conflictList = conflictColumns.joinToString(", ") { it.name }
                val updateSet = updateCols.joinToString(", ") { "${it.name} = EXCLUDED.${it.name}" }
                if (updateCols.isEmpty()) {
                    "INSERT INTO ${table.tableName} ($colNames) VALUES ($placeholders) ON CONFLICT ($conflictList) DO NOTHING"
                } else {
                    "INSERT INTO ${table.tableName} ($colNames) VALUES ($placeholders) ON CONFLICT ($conflictList) DO UPDATE SET $updateSet"
                }
            }
            DatabaseDialect.H2 -> {
                val colNames = allCols.joinToString(", ") { it.name }
                val placeholders = allCols.joinToString(", ") { "?" }
                val keyList = conflictColumns.joinToString(", ") { it.name }
                "MERGE INTO ${table.tableName} ($colNames) KEY ($keyList) VALUES ($placeholders)"
            }
        }

        return conn.prepareStatement(sql).use { ps ->
            columnValues.forEachIndexed { index, (col, value) ->
                (col.sqlType as SqlType<Any?>).set(ps, index + 1, value)
            }
            ps.executeUpdate()
        }
    }
}
