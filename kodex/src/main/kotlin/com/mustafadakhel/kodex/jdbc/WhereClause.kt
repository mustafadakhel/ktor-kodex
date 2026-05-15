package com.mustafadakhel.kodex.jdbc

import java.sql.PreparedStatement

public class BoundParam(
    @PublishedApi internal val sqlType: SqlType<*>,
    @PublishedApi internal val value: Any?,
) {
    @Suppress("UNCHECKED_CAST")
    public fun bind(ps: PreparedStatement, index: Int) {
        (sqlType as SqlType<Any?>).set(ps, index, value)
    }
}

public data class WhereClause(val sql: String, val params: List<BoundParam>) {
    public companion object {
        public val FALSE: WhereClause = WhereClause("FALSE", emptyList())
    }
}

public infix fun <T> Column<T>.eq(value: T): WhereClause =
    WhereClause("${qualifiedName} = ?", listOf(BoundParam(sqlType, value)))

public infix fun <T> Column<T>.neq(value: T): WhereClause =
    WhereClause("${qualifiedName} <> ?", listOf(BoundParam(sqlType, value)))

public infix fun <T : Comparable<T>> Column<T>.less(value: T): WhereClause =
    WhereClause("${qualifiedName} < ?", listOf(BoundParam(sqlType, value)))

public infix fun <T : Comparable<T>> Column<T>.greater(value: T): WhereClause =
    WhereClause("${qualifiedName} > ?", listOf(BoundParam(sqlType, value)))

public infix fun <T> Column<T>.inList(values: List<T>): WhereClause =
    if (values.isEmpty()) WhereClause.FALSE
    else WhereClause(
        "${qualifiedName} IN (${values.joinToString(", ") { "?" }})",
        values.map { BoundParam(sqlType, it) },
    )

public fun <T> Column<T?>.isNull(): WhereClause =
    WhereClause("${qualifiedName} IS NULL", emptyList())

public fun <T> Column<T?>.isNotNull(): WhereClause =
    WhereClause("${qualifiedName} IS NOT NULL", emptyList())

public infix fun <T> Column<T>.eqColumn(other: Column<T>): WhereClause =
    WhereClause("${qualifiedName} = ${other.qualifiedName}", emptyList())

public infix fun WhereClause.and(other: WhereClause): WhereClause =
    WhereClause("(${this.sql}) AND (${other.sql})", this.params + other.params)

public infix fun WhereClause.or(other: WhereClause): WhereClause =
    WhereClause("(${this.sql}) OR (${other.sql})", this.params + other.params)

public enum class SortOrder {
    ASC,
    DESC,
}

internal fun List<BoundParam>.bindTo(ps: PreparedStatement, startIndex: Int = 1): Int {
    forEachIndexed { index, param -> param.bind(ps, startIndex + index) }
    return startIndex + size
}
