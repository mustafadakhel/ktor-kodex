package com.mustafadakhel.kodex.jdbc

public class Column<T>(
    public val name: String,
    public val sqlType: SqlType<T>,
    public val table: TableDef,
    public val nullable: Boolean = false,
    public val defaultExpression: String? = null,
    public val autoGenerate: Boolean = false,
    public val references: ForeignKeyDef? = null,
    public val deferredReferences: DeferredForeignKeyDef? = null,
) {
    init {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) {
            "Column name must contain only letters, digits, or underscores. Got: \"$name\""
        }
    }

    public val qualifiedName: String = "${table.tableName}.${name}"
}
