package com.mustafadakhel.kodex.jdbc

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toKotlinLocalDateTime
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID

public interface SqlType<T> {
    public val sqlName: String
    public fun set(ps: PreparedStatement, index: Int, value: T)
    public fun get(rs: ResultSet, name: String): T
    public fun ddl(dialect: DatabaseDialect): String = sqlName
}

public class VarcharType(public val length: Int) : SqlType<String> {
    override val sqlName: String = "VARCHAR($length)"
    override fun set(ps: PreparedStatement, index: Int, value: String): Unit = ps.setString(index, value)
    override fun get(rs: ResultSet, name: String): String = rs.getString(name)
}

public object TextType : SqlType<String> {
    override val sqlName: String = "TEXT"
    override fun set(ps: PreparedStatement, index: Int, value: String): Unit = ps.setString(index, value)
    override fun get(rs: ResultSet, name: String): String = rs.getString(name)
}

public object UuidType : SqlType<UUID> {
    override val sqlName: String = "UUID"
    override fun set(ps: PreparedStatement, index: Int, value: UUID): Unit = ps.setObject(index, value)
    override fun get(rs: ResultSet, name: String): UUID = rs.getObject(name, UUID::class.java)
}

public object BoolType : SqlType<Boolean> {
    override val sqlName: String = "BOOLEAN"
    override fun set(ps: PreparedStatement, index: Int, value: Boolean): Unit = ps.setBoolean(index, value)
    override fun get(rs: ResultSet, name: String): Boolean = rs.getBoolean(name)
}

public object IntType : SqlType<Int> {
    override val sqlName: String = "INTEGER"
    override fun set(ps: PreparedStatement, index: Int, value: Int): Unit = ps.setInt(index, value)
    override fun get(rs: ResultSet, name: String): Int = rs.getInt(name)
}

public class DecimalType(public val precision: Int, public val scale: Int) : SqlType<BigDecimal> {
    override val sqlName: String = "DECIMAL($precision,$scale)"
    override fun set(ps: PreparedStatement, index: Int, value: BigDecimal): Unit = ps.setBigDecimal(index, value)
    override fun get(rs: ResultSet, name: String): BigDecimal = rs.getBigDecimal(name)
}

public object InstantType : SqlType<Instant> {
    override val sqlName: String = "TIMESTAMP"

    override fun set(ps: PreparedStatement, index: Int, value: Instant) {
        ps.setTimestamp(index, Timestamp.from(value.toJavaInstant()))
    }

    override fun get(rs: ResultSet, name: String): Instant =
        rs.getTimestamp(name).toInstant().toKotlinInstant()
}

public object LocalDateTimeType : SqlType<LocalDateTime> {
    override val sqlName: String = "TIMESTAMP"

    override fun set(ps: PreparedStatement, index: Int, value: LocalDateTime) {
        ps.setTimestamp(index, Timestamp.valueOf(value.toJavaLocalDateTime()))
    }

    override fun get(rs: ResultSet, name: String): LocalDateTime =
        rs.getTimestamp(name).toLocalDateTime().toKotlinLocalDateTime()
}

public class EnumByNameType<E : Enum<E>>(
    public val length: Int,
    private val enumClass: Class<E>,
    private val enumConstants: Array<E>,
) : SqlType<E> {
    init {
        for (constant in enumConstants) {
            require(constant.name.length <= length) {
                "Enum constant '${constant.name}' (${constant.name.length} chars) " +
                    "exceeds VARCHAR($length) for ${enumClass.simpleName}."
            }
        }
    }

    override val sqlName: String = "VARCHAR($length)"

    override fun set(ps: PreparedStatement, index: Int, value: E): Unit = ps.setString(index, value.name)

    override fun get(rs: ResultSet, name: String): E {
        val str = rs.getString(name)
        return enumConstants.firstOrNull { it.name == str }
            ?: error("Unknown ${enumClass.simpleName} value: \"$str\"")
    }

    public companion object {
        public inline fun <reified E : Enum<E>> create(length: Int): EnumByNameType<E> =
            EnumByNameType(length, E::class.java, enumValues<E>())
    }
}

public class NullableSqlType<T : Any>(public val inner: SqlType<T>) : SqlType<T?> {
    override val sqlName: String get() = inner.sqlName

    override fun set(ps: PreparedStatement, index: Int, value: T?) {
        if (value == null) ps.setNull(index, Types.NULL) else inner.set(ps, index, value)
    }

    override fun get(rs: ResultSet, name: String): T? {
        rs.getObject(name) ?: return null
        return inner.get(rs, name)
    }

    override fun ddl(dialect: DatabaseDialect): String = inner.ddl(dialect)
}
