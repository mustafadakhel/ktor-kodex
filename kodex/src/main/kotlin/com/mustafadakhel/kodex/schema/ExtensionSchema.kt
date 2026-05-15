package com.mustafadakhel.kodex.schema

import com.mustafadakhel.kodex.jdbc.DatabaseDialect

public interface ExtensionSchema {
    public fun ddl(dialect: DatabaseDialect): List<String>
    public fun tableNames(): List<String>
}
