package com.mustafadakhel.kodex.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

class TableDefTest {

    private enum class TestStatus { ACTIVE, INACTIVE }

    private class UsersTable : TableDef("test_users") {
        val id = uuid("id").autoGenerate()
        val name = varchar("name", 100)
        val email = varchar("email", 255)
        val active = bool("active").default("TRUE")
        val createdAt = timestamp("created_at")
        override val primaryKey = PrimaryKeyDef(id)
    }

    private class OrdersTable(users: UsersTable) : TableDef("test_orders") {
        val id = uuid("id").autoGenerate()
        val userId = uuid("user_id").references(users.id, ReferenceAction.CASCADE)
        val amount = decimal("amount", 10, 2)
        override val primaryKey = PrimaryKeyDef(id)
    }

    private class JoinTable : TableDef("test_join") {
        val a = uuid("a_id")
        val b = uuid("b_id")
        override val primaryKey = PrimaryKeyDef(a, b)
        init { uniqueIndex(a, b, name = "uidx_test_ab") }
    }

    private class NullableTable : TableDef("test_nullable") {
        val id = integer("id").autoGenerate()
        val bio = text("bio").nullable()
        val updatedAt = datetime("updated_at").nullable().default("CURRENT_TIMESTAMP")
        override val primaryKey = PrimaryKeyDef(id)
    }

    @Test
    fun `createTableDDL for basic table with UUID autoGenerate on H2`() {
        val users = UsersTable()
        val ddl = users.createTableDDL(DatabaseDialect.H2)
        ddl shouldContain "CREATE TABLE IF NOT EXISTS test_users"
        ddl shouldContain "id UUID DEFAULT RANDOM_UUID()"
        ddl shouldContain "name VARCHAR(100) NOT NULL"
        ddl shouldContain "email VARCHAR(255) NOT NULL"
        ddl shouldContain "active BOOLEAN NOT NULL DEFAULT TRUE"
        ddl shouldContain "created_at TIMESTAMP NOT NULL"
        ddl shouldContain "PRIMARY KEY (id)"
    }

    @Test
    fun `createTableDDL for basic table with UUID autoGenerate on PostgreSQL`() {
        val users = UsersTable()
        val ddl = users.createTableDDL(DatabaseDialect.POSTGRESQL)
        ddl shouldContain "id UUID DEFAULT gen_random_uuid()"
        ddl shouldContain "name VARCHAR(100) NOT NULL"
    }

    @Test
    fun `createTableDDL with FK and CASCADE`() {
        val users = UsersTable()
        val orders = OrdersTable(users)
        val ddl = orders.createTableDDL(DatabaseDialect.H2)
        ddl shouldContain "FOREIGN KEY (user_id) REFERENCES test_users(id) ON DELETE CASCADE"
        ddl shouldContain "amount DECIMAL(10,2) NOT NULL"
    }

    @Test
    fun `createTableDDL with composite primary key`() {
        val join = JoinTable()
        val ddl = join.createTableDDL(DatabaseDialect.H2)
        ddl shouldContain "PRIMARY KEY (a_id, b_id)"
    }

    @Test
    fun `createIndexDDL with unique index`() {
        val join = JoinTable()
        val indexDdl = join.createIndexDDL(DatabaseDialect.H2)
        indexDdl.size shouldBe 1
        indexDdl[0] shouldContain "CREATE UNIQUE INDEX IF NOT EXISTS uidx_test_ab ON test_join (a_id, b_id)"
    }

    @Test
    fun `nullable column DDL omits NOT NULL`() {
        val table = NullableTable()
        val ddl = table.createTableDDL(DatabaseDialect.H2)
        // bio should NOT have NOT NULL
        ddl shouldContain "bio TEXT,"  // no NOT NULL before comma
        ddl shouldNotContain "bio TEXT NOT NULL"
    }

    @Test
    fun `nullable column with default`() {
        val table = NullableTable()
        val ddl = table.createTableDDL(DatabaseDialect.H2)
        ddl shouldContain "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
    }

    @Test
    fun `Int autoGenerate on H2`() {
        val table = NullableTable()
        val ddl = table.createTableDDL(DatabaseDialect.H2)
        ddl shouldContain "id INTEGER AUTO_INCREMENT"
    }

    @Test
    fun `Int autoGenerate on PostgreSQL`() {
        val table = NullableTable()
        val ddl = table.createTableDDL(DatabaseDialect.POSTGRESQL)
        ddl shouldContain "id INTEGER GENERATED ALWAYS AS IDENTITY"
    }

    @Test
    fun `invalid table name throws`() {
        shouldThrow<IllegalArgumentException> {
            object : TableDef("bad name") {
                override val primaryKey = PrimaryKeyDef(emptyList())
            }
        }
    }

    @Test
    fun `valid table name with underscores and digits succeeds`() {
        val table = object : TableDef("my_table_123") {
            override val primaryKey = PrimaryKeyDef(emptyList())
        }
        table.tableName shouldBe "my_table_123"
    }
}
