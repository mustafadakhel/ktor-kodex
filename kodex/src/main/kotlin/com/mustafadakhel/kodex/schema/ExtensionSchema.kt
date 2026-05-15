package com.mustafadakhel.kodex.schema

import org.jetbrains.exposed.sql.Table

public interface ExtensionSchema {
    public fun tables(): List<Table>
}
