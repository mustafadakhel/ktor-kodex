package com.mustafadakhel.kodex.schema

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.TableDef

public interface ExtensionSchema {
    public fun tables(): List<TableDef>

    public fun ddl(dialect: DatabaseDialect): List<String> =
        tables().flatMap { listOf(it.createTableDDL(dialect)) + it.createIndexDDL(dialect) }

    public fun tableNames(): List<String> =
        tables().map { it.tableName }
}
