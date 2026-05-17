package com.mustafadakhel.kodex.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ColumnTest {

    private val stubTable = object : TableDef("test_table") {
        override val primaryKey: PrimaryKeyDef get() = PrimaryKeyDef(emptyList())
    }

    @Test
    fun `valid column name constructs successfully`() {
        val col = Column("user_name_123", VarcharType(255), stubTable)
        col.name shouldBe "user_name_123"
    }

    @Test
    fun `column name with spaces throws`() {
        val ex = shouldThrow<IllegalArgumentException> {
            Column("user name", VarcharType(255), stubTable)
        }
        ex.message shouldContain "user name"
    }

    @Test
    fun `column name with hyphens throws`() {
        shouldThrow<IllegalArgumentException> {
            Column("user-name", VarcharType(255), stubTable)
        }
    }

    @Test
    fun `column name with dots throws`() {
        shouldThrow<IllegalArgumentException> {
            Column("user.name", VarcharType(255), stubTable)
        }
    }

    @Test
    fun `qualifiedName returns tableName dot columnName`() {
        val col = Column("email", VarcharType(255), stubTable)
        col.qualifiedName shouldBe "test_table.email"
    }
}
