package com.mustafadakhel.kodex.jdbc

import java.sql.ResultSet

@InternalKodexApi
public class Row(@PublishedApi internal val rs: ResultSet) {
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(column: Column<T>): T = column.sqlType.get(rs, column.name) as T
}
