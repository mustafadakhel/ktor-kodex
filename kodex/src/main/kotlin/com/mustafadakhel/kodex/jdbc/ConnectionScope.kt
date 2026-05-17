@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.jdbc

import java.sql.Connection

@InternalKodexApi
@KodexJdbcDsl
public class ConnectionScope(
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

    /**
     * Insert a row, silently ignoring the insert if a conflict occurs on [conflictColumns].
     * Returns 1 if inserted, 0 if the row already existed.
     */
    @Suppress("UNCHECKED_CAST")
    public fun insertOrIgnore(table: TableDef, conflictColumns: List<Column<*>>, block: InsertBuilder.() -> Unit): Int {
        val builder = InsertBuilder(table, conn).apply(block)
        val columnValues = builder.getColumnValues()
        val allCols = columnValues.map { it.first }

        val sql = when (dialect) {
            DatabaseDialect.POSTGRESQL -> {
                val colNames = allCols.joinToString(", ") { it.name }
                val placeholders = allCols.joinToString(", ") { "?" }
                val conflictList = conflictColumns.joinToString(", ") { it.name }
                "INSERT INTO ${table.tableName} ($colNames) VALUES ($placeholders) ON CONFLICT ($conflictList) DO NOTHING"
            }
            DatabaseDialect.H2 -> {
                val colNames = allCols.joinToString(", ") { it.name }
                val placeholders = allCols.joinToString(", ") { "?" }
                val conflictCheck = conflictColumns.joinToString(" AND ") { "${it.name} = ?" }
                "INSERT INTO ${table.tableName} ($colNames) SELECT $placeholders " +
                    "WHERE NOT EXISTS (SELECT 1 FROM ${table.tableName} WHERE $conflictCheck)"
            }
        }

        return conn.prepareStatement(sql).use { ps ->
            columnValues.forEachIndexed { index, (col, value) ->
                (col.sqlType as SqlType<Any?>).set(ps, index + 1, value)
            }
            if (dialect == DatabaseDialect.H2) {
                val baseOffset = columnValues.size
                conflictColumns.forEachIndexed { index, conflictCol ->
                    val value = columnValues.first { it.first.name == conflictCol.name }.second
                    (conflictCol.sqlType as SqlType<Any?>).set(ps, baseOffset + index + 1, value)
                }
            }
            ps.executeUpdate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    public fun upsert(table: TableDef, conflictColumns: List<Column<*>>, block: InsertBuilder.() -> Unit): Int {
        val builder = InsertBuilder(table, conn).apply(block)
        val columnValues = builder.getColumnValues()
        val allCols = columnValues.map { it.first }
        val conflictNames = conflictColumns.map { it.name }.toSet()
        val updateCols = allCols.filter { it.name !in conflictNames }

        val colNames = allCols.joinToString(", ") { it.name }
        val placeholders = allCols.joinToString(", ") { "?" }

        val sql = when (dialect) {
            DatabaseDialect.POSTGRESQL -> {
                val conflictList = conflictColumns.joinToString(", ") { it.name }
                if (updateCols.isEmpty()) {
                    "INSERT INTO ${table.tableName} ($colNames) VALUES ($placeholders) ON CONFLICT ($conflictList) DO NOTHING"
                } else {
                    val updateSet = updateCols.joinToString(", ") { "${it.name} = EXCLUDED.${it.name}" }
                    "INSERT INTO ${table.tableName} ($colNames) VALUES ($placeholders) ON CONFLICT ($conflictList) DO UPDATE SET $updateSet"
                }
            }
            DatabaseDialect.H2 -> {
                if (updateCols.isEmpty()) {
                    val keyList = conflictColumns.joinToString(", ") { it.name }
                    "MERGE INTO ${table.tableName} ($colNames) KEY ($keyList) VALUES ($placeholders)"
                } else {
                    val keyCondition = conflictColumns.joinToString(" AND ") { "t.${it.name} = s.${it.name}" }
                    val updateSet = updateCols.joinToString(", ") { "t.${it.name} = s.${it.name}" }
                    val insertCols = allCols.joinToString(", ") { "s.${it.name}" }
                    "MERGE INTO ${table.tableName} t USING (VALUES ($placeholders)) AS s($colNames) " +
                        "ON ($keyCondition) " +
                        "WHEN MATCHED THEN UPDATE SET $updateSet " +
                        "WHEN NOT MATCHED THEN INSERT ($colNames) VALUES ($insertCols)"
                }
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
