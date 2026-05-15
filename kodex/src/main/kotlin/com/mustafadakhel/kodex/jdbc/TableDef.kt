package com.mustafadakhel.kodex.jdbc

import java.math.BigDecimal
import java.util.UUID
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime

public enum class ReferenceAction(public val sql: String) {
    NO_ACTION("NO ACTION"),
    CASCADE("CASCADE"),
    SET_NULL("SET NULL"),
    RESTRICT("RESTRICT"),
}

public data class ForeignKeyDef(
    public val targetColumn: Column<*>,
    public val onDelete: ReferenceAction,
)

public data class IndexDef(
    public val name: String,
    public val columns: List<Column<*>>,
    public val unique: Boolean,
)

public data class PrimaryKeyDef(public val columns: List<Column<*>>) {
    public constructor(vararg cols: Column<*>) : this(cols.toList())
}

public abstract class TableDef(public val tableName: String) {
    init {
        require(tableName.isNotEmpty() && tableName.all { it.isLetterOrDigit() || it == '_' }) {
            "Table name must contain only letters, digits, or underscores. Got: \"$tableName\""
        }
    }

    internal val columns: MutableList<Column<*>> = mutableListOf()
    internal val indexes: MutableList<IndexDef> = mutableListOf()
    public abstract val primaryKey: PrimaryKeyDef

    @PublishedApi internal fun <T> register(column: Column<T>): Column<T> {
        columns.add(column)
        return column
    }

    private fun <T> replaceColumn(old: Column<T>, new: Column<*>): Int {
        val index = columns.indexOf(old)
        check(index >= 0) {
            "Column '${old.name}' is not registered in table '$tableName'. Was a modifier called on a stale reference?"
        }
        columns[index] = new
        return index
    }

    protected fun varchar(name: String, length: Int): Column<String> =
        register(Column(name, VarcharType(length), this))

    protected fun text(name: String): Column<String> =
        register(Column(name, TextType, this))

    protected fun uuid(name: String): Column<UUID> =
        register(Column(name, UuidType, this))

    protected fun bool(name: String): Column<Boolean> =
        register(Column(name, BoolType, this))

    protected fun integer(name: String): Column<Int> =
        register(Column(name, IntType, this))

    protected fun decimal(name: String, precision: Int, scale: Int): Column<BigDecimal> =
        register(Column(name, DecimalType(precision, scale), this))

    protected fun timestamp(name: String): Column<Instant> =
        register(Column(name, InstantType, this))

    protected fun datetime(name: String): Column<LocalDateTime> =
        register(Column(name, LocalDateTimeType, this))

    protected inline fun <reified E : Enum<E>> enumByName(name: String, length: Int): Column<E> =
        register(Column(name, EnumByNameType.create<E>(length), this))

    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> Column<T>.nullable(): Column<T?> {
        val nullable = Column(name, NullableSqlType(sqlType), table, nullable = true, defaultExpression, autoGenerate, references)
        replaceColumn(this, nullable)
        return nullable as Column<T?>
    }

    public fun <T> Column<T>.default(expression: String): Column<T> {
        val withDefault = Column(name, sqlType, table, nullable, expression, autoGenerate, references)
        replaceColumn(this, withDefault)
        return withDefault
    }

    public fun <T> Column<T>.autoGenerate(): Column<T> {
        val withAuto = Column(name, sqlType, table, nullable, defaultExpression, autoGenerate = true, references)
        replaceColumn(this, withAuto)
        return withAuto
    }

    public fun <T> Column<T>.index(name: String? = null): Column<T> {
        val indexName = name ?: "idx_${tableName}_${this.name}"
        indexes.add(IndexDef(indexName, listOf(this), unique = false))
        return this
    }

    public fun <T> Column<T>.references(
        otherColumn: Column<T>,
        onDelete: ReferenceAction = ReferenceAction.NO_ACTION,
    ): Column<T> {
        val withRef = Column(this.name, sqlType, table, nullable, defaultExpression, autoGenerate, ForeignKeyDef(otherColumn, onDelete))
        replaceColumn(this, withRef)
        return withRef
    }

    protected fun index(vararg cols: Column<*>, unique: Boolean = false, name: String? = null): IndexDef {
        val indexName = name ?: buildString {
            append(if (unique) "uidx_" else "idx_")
            append(tableName)
            cols.forEach { append("_${it.name}") }
        }
        val def = IndexDef(indexName, cols.toList(), unique)
        indexes.add(def)
        return def
    }

    protected fun uniqueIndex(vararg cols: Column<*>, name: String? = null): IndexDef =
        index(*cols, unique = true, name = name)

    public fun createTableDDL(dialect: DatabaseDialect): String = buildString {
        append("CREATE TABLE IF NOT EXISTS $tableName (")
        val defs = mutableListOf<String>()
        for (col in columns) {
            defs.add(columnDDL(col, dialect))
        }
        defs.add("PRIMARY KEY (${primaryKey.columns.joinToString(", ") { it.name }})")
        for (col in columns) {
            val fk = col.references ?: continue
            defs.add("FOREIGN KEY (${col.name}) REFERENCES ${fk.targetColumn.table.tableName}(${fk.targetColumn.name}) ON DELETE ${fk.onDelete.sql}")
        }
        append(defs.joinToString(", "))
        append(")")
    }

    public fun createIndexDDL(@Suppress("UNUSED_PARAMETER") dialect: DatabaseDialect): List<String> =
        indexes.map { idx ->
            val unique = if (idx.unique) "UNIQUE " else ""
            val cols = idx.columns.joinToString(", ") { it.name }
            "CREATE ${unique}INDEX IF NOT EXISTS ${idx.name} ON $tableName ($cols)"
        }

    private fun columnDDL(col: Column<*>, dialect: DatabaseDialect): String = buildString {
        append(col.name)
        append(" ")
        append(col.sqlType.ddl(dialect))
        if (col.autoGenerate) {
            append(autoGenerateDDL(col, dialect))
        }
        if (!col.nullable && !col.autoGenerate) {
            append(" NOT NULL")
        }
        if (col.defaultExpression != null && !col.autoGenerate) {
            append(" DEFAULT ${col.defaultExpression}")
        }
    }

    private fun autoGenerateDDL(col: Column<*>, dialect: DatabaseDialect): String {
        val baseType = when (val t = col.sqlType) {
            is NullableSqlType<*> -> t.inner
            else -> t
        }
        return when {
            baseType is UuidType && dialect == DatabaseDialect.H2 -> " DEFAULT RANDOM_UUID()"
            baseType is UuidType && dialect == DatabaseDialect.POSTGRESQL -> " DEFAULT gen_random_uuid()"
            baseType is IntType && dialect == DatabaseDialect.H2 -> " AUTO_INCREMENT"
            baseType is IntType && dialect == DatabaseDialect.POSTGRESQL -> " GENERATED ALWAYS AS IDENTITY"
            else -> ""
        }
    }
}
