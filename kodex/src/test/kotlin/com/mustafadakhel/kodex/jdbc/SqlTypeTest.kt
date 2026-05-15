package com.mustafadakhel.kodex.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlTypeTest {

    private lateinit var conn: Connection

    @BeforeAll
    fun setup() {
        val ds = JdbcDataSource().apply { setUrl("jdbc:h2:mem:sqltype_test;DB_CLOSE_DELAY=-1") }
        conn = ds.connection
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS type_test (
                    id UUID PRIMARY KEY,
                    str_val VARCHAR(255),
                    text_val TEXT,
                    bool_val BOOLEAN,
                    int_val INTEGER,
                    decimal_val DECIMAL(10,2),
                    instant_val TIMESTAMP,
                    datetime_val TIMESTAMP,
                    enum_val VARCHAR(50),
                    nullable_str VARCHAR(255)
                )
                """.trimIndent()
            )
        }
    }

    @AfterAll
    fun teardown() {
        conn.close()
    }

    private fun insertAndRead(block: (Connection) -> Unit): java.sql.ResultSet {
        block(conn)
        val rs = conn.createStatement().executeQuery("SELECT * FROM type_test LIMIT 1")
        rs.next()
        return rs
    }

    @Test
    fun `VarcharType round-trip`() {
        val type = VarcharType(255)
        val id = UUID.randomUUID()
        conn.createStatement().execute("DELETE FROM type_test")
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, str_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                type.set(ps, 2, "hello")
                ps.executeUpdate()
            }
        }
        type.get(rs, "str_val") shouldBe "hello"
        rs.close()
    }

    @Test
    fun `TextType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, text_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                TextType.set(ps, 2, "long text value")
                ps.executeUpdate()
            }
        }
        TextType.get(rs, "text_val") shouldBe "long text value"
        rs.close()
    }

    @Test
    fun `UuidType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id) VALUES (?)").use { ps ->
                UuidType.set(ps, 1, id)
                ps.executeUpdate()
            }
        }
        UuidType.get(rs, "id") shouldBe id
        rs.close()
    }

    @Test
    fun `BoolType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, bool_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                BoolType.set(ps, 2, true)
                ps.executeUpdate()
            }
        }
        BoolType.get(rs, "bool_val") shouldBe true
        rs.close()
    }

    @Test
    fun `IntType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, int_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                IntType.set(ps, 2, 42)
                ps.executeUpdate()
            }
        }
        IntType.get(rs, "int_val") shouldBe 42
        rs.close()
    }

    @Test
    fun `DecimalType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val type = DecimalType(10, 2)
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, decimal_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                type.set(ps, 2, BigDecimal("123.45"))
                ps.executeUpdate()
            }
        }
        type.get(rs, "decimal_val").compareTo(BigDecimal("123.45")) shouldBe 0
        rs.close()
    }

    @Test
    fun `InstantType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val id = UUID.randomUUID()
        val now = Clock.System.now()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, instant_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                InstantType.set(ps, 2, now)
                ps.executeUpdate()
            }
        }
        val read = InstantType.get(rs, "instant_val")
        // Timestamp precision may truncate nanos, compare to millisecond
        (read.toEpochMilliseconds() - now.toEpochMilliseconds()) shouldBe 0L
        rs.close()
    }

    @Test
    fun `LocalDateTimeType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val id = UUID.randomUUID()
        val dt = LocalDateTime(2026, 5, 15, 12, 30, 0, 0)
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, datetime_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                LocalDateTimeType.set(ps, 2, dt)
                ps.executeUpdate()
            }
        }
        LocalDateTimeType.get(rs, "datetime_val") shouldBe dt
        rs.close()
    }

    private enum class TestStatus { ACTIVE, INACTIVE }

    @Test
    fun `EnumByNameType round-trip`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val type = EnumByNameType.create<TestStatus>(50)
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, enum_val) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                type.set(ps, 2, TestStatus.ACTIVE)
                ps.executeUpdate()
            }
        }
        type.get(rs, "enum_val") shouldBe TestStatus.ACTIVE
        rs.close()
    }

    @Test
    fun `EnumByNameType rejects oversized enum constants`() {
        val ex = shouldThrow<IllegalArgumentException> {
            EnumByNameType.create<TestStatus>(3) // "ACTIVE" is 6 chars, exceeds 3
        }
        ex.message shouldContain "ACTIVE"
        ex.message shouldContain "exceeds VARCHAR(3)"
    }

    @Test
    fun `NullableSqlType handles null value`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val type = NullableSqlType(VarcharType(255))
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, nullable_str) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                type.set(ps, 2, null)
                ps.executeUpdate()
            }
        }
        type.get(rs, "nullable_str").shouldBeNull()
        rs.close()
    }

    @Test
    fun `NullableSqlType handles non-null value`() {
        conn.createStatement().execute("DELETE FROM type_test")
        val type = NullableSqlType(VarcharType(255))
        val id = UUID.randomUUID()
        val rs = insertAndRead { c ->
            c.prepareStatement("INSERT INTO type_test (id, nullable_str) VALUES (?, ?)").use { ps ->
                UuidType.set(ps, 1, id)
                type.set(ps, 2, "not null")
                ps.executeUpdate()
            }
        }
        type.get(rs, "nullable_str") shouldBe "not null"
        rs.close()
    }
}
