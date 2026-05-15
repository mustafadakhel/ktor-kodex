package com.mustafadakhel.kodex.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import javax.sql.DataSource

class DatabaseDialectTest {

    @Test
    fun `detect returns H2 for H2 DataSource`() {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:dialect_test;DB_CLOSE_DELAY=-1")
        }
        DatabaseDialect.detect(ds) shouldBe DatabaseDialect.H2
    }

    @Test
    fun `detect returns POSTGRESQL for PostgreSQL metadata`() {
        val metaData = mockk<DatabaseMetaData> {
            every { databaseProductName } returns "PostgreSQL"
        }
        val conn = mockk<Connection> {
            every { this@mockk.metaData } returns metaData
            every { close() } returns Unit
        }
        val ds = mockk<DataSource> {
            every { connection } returns conn
        }
        DatabaseDialect.detect(ds) shouldBe DatabaseDialect.POSTGRESQL
    }

    @Test
    fun `detect throws for unsupported database`() {
        val metaData = mockk<DatabaseMetaData> {
            every { databaseProductName } returns "MySQL"
        }
        val conn = mockk<Connection> {
            every { this@mockk.metaData } returns metaData
            every { close() } returns Unit
        }
        val ds = mockk<DataSource> {
            every { connection } returns conn
        }
        val ex = shouldThrow<IllegalStateException> {
            DatabaseDialect.detect(ds)
        }
        ex.message shouldContain "Unsupported database"
        ex.message shouldContain "mysql"
    }

    @Test
    fun `detect wraps SQLException with actionable message`() {
        val ds = mockk<DataSource> {
            every { connection } throws SQLException("Connection refused")
        }
        val ex = shouldThrow<IllegalStateException> {
            DatabaseDialect.detect(ds)
        }
        ex.message shouldContain "Failed to detect database dialect"
        ex.message shouldContain "Connection refused"
    }
}
