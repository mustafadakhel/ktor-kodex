package com.mustafadakhel.kodex.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class BuilderIntegrationTest {

    private val users = TestUsersTable()
    private val orders = TestOrdersTable(users)
    private lateinit var ds: DataSource
    private lateinit var conn: Connection

    @BeforeEach
    fun setup() {
        ds = createTestDataSource()
        ds.createTestTables(users, orders)
        conn = ds.connection
        conn.autoCommit = false
    }

    @AfterEach
    fun teardown() {
        conn.rollback()
        conn.close()
    }

    private fun insertUser(name: String, email: String, age: Int? = null): UUID {
        val builder = InsertBuilder(users, conn)
        builder[users.id] = UUID.randomUUID()
        builder[users.name] = name
        builder[users.email] = email
        if (age != null) builder[users.age] = age
        return builder.executeAndReturnKey(users.id)
    }

    @Nested
    inner class InsertBuilderTests {

        @Test
        fun `execute inserts a row`() {
            val builder = InsertBuilder(users, conn)
            val id = UUID.randomUUID()
            builder[users.id] = id
            builder[users.name] = "Alice"
            builder[users.email] = "alice@test.com"
            builder.execute()

            val select = SelectBuilder(users, conn, DatabaseDialect.H2)
            val found = select.where { users.id eq id }.any()
            found shouldBe true
        }

        @Test
        fun `executeAndReturnKey returns UUID`() {
            val id = insertUser("Bob", "bob@test.com")
            id.shouldNotBeNull()
        }

        @Test
        fun `nullable column with null value`() {
            val id = insertUser("Carol", "carol@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.id eq id }
                .firstOrNull { row -> row[users.age] }
            result.shouldBeNull()
        }
    }

    @Nested
    inner class SelectBuilderTests {

        @Test
        fun `map returns all matching rows`() {
            repeat(5) { i -> insertUser("User$i", "user$i@test.com") }
            val results = SelectBuilder(users, conn, DatabaseDialect.H2).map { row -> row[users.name] }
            results shouldHaveSize 5
        }

        @Test
        fun `firstOrNull returns first matching`() {
            insertUser("Alice", "alice@test.com")
            insertUser("Bob", "bob@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Alice" }
                .firstOrNull { row -> row[users.name] }
            result shouldBe "Alice"
        }

        @Test
        fun `firstOrNull returns null when no match`() {
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Nobody" }
                .firstOrNull { row -> row[users.name] }
            result.shouldBeNull()
        }

        @Test
        fun `singleOrNull returns one row`() {
            insertUser("Alice", "alice@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Alice" }
                .singleOrNull { row -> row[users.name] }
            result shouldBe "Alice"
        }

        @Test
        fun `singleOrNull throws on multiple rows`() {
            insertUser("Alice", "a1@test.com")
            insertUser("Alice", "a2@test.com")
            shouldThrow<IllegalStateException> {
                SelectBuilder(users, conn, DatabaseDialect.H2)
                    .where { users.name eq "Alice" }
                    .singleOrNull { row -> row[users.name] }
            }
        }

        @Test
        fun `any returns true when exists`() {
            insertUser("Alice", "alice@test.com")
            SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Alice" }
                .any() shouldBe true
        }

        @Test
        fun `any returns false when empty`() {
            SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Nobody" }
                .any() shouldBe false
        }

        @Test
        fun `count returns correct count`() {
            repeat(3) { i -> insertUser("User$i", "user$i@test.com") }
            SelectBuilder(users, conn, DatabaseDialect.H2).count() shouldBe 3
        }

        @Test
        fun `where filters results`() {
            insertUser("Alice", "alice@test.com")
            insertUser("Bob", "bob@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Bob" }
                .map { row -> row[users.name] }
            result shouldBe listOf("Bob")
        }

        @Test
        fun `orderBy ASC`() {
            insertUser("Charlie", "c@test.com")
            insertUser("Alice", "a@test.com")
            insertUser("Bob", "b@test.com")
            val names = SelectBuilder(users, conn, DatabaseDialect.H2)
                .orderBy(users.name, SortOrder.ASC)
                .map { row -> row[users.name] }
            names shouldBe listOf("Alice", "Bob", "Charlie")
        }

        @Test
        fun `orderBy DESC`() {
            insertUser("Charlie", "c@test.com")
            insertUser("Alice", "a@test.com")
            insertUser("Bob", "b@test.com")
            val names = SelectBuilder(users, conn, DatabaseDialect.H2)
                .orderBy(users.name, SortOrder.DESC)
                .map { row -> row[users.name] }
            names shouldBe listOf("Charlie", "Bob", "Alice")
        }

        @Test
        fun `limit restricts results`() {
            repeat(5) { i -> insertUser("User$i", "user$i@test.com") }
            val results = SelectBuilder(users, conn, DatabaseDialect.H2)
                .limit(2)
                .map { row -> row[users.name] }
            results shouldHaveSize 2
        }

        @Test
        fun `offset skips rows`() {
            insertUser("A", "a@test.com")
            insertUser("B", "b@test.com")
            insertUser("C", "c@test.com")
            val results = SelectBuilder(users, conn, DatabaseDialect.H2)
                .orderBy(users.name, SortOrder.ASC)
                .offset(1)
                .limit(2)
                .map { row -> row[users.name] }
            results shouldBe listOf("B", "C")
        }

        @Test
        fun `forUpdate executes without error`() {
            insertUser("Alice", "alice@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Alice" }
                .forUpdate()
                .firstOrNull { row -> row[users.name] }
            result shouldBe "Alice"
        }

        @Test
        fun `innerJoin returns joined rows`() {
            val userId = insertUser("Alice", "alice@test.com")
            val orderBuilder = InsertBuilder(orders, conn)
            orderBuilder[orders.id] = UUID.randomUUID()
            orderBuilder[orders.userId] = userId
            orderBuilder[orders.amount] = java.math.BigDecimal("99.99")
            orderBuilder[orders.orderedAt] = kotlinx.datetime.Clock.System.now()
            orderBuilder.execute()

            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .innerJoin(orders) { orders.userId eqColumn users.id }
                .where { users.name eq "Alice" }
                .firstOrNull { row -> row[orders.amount] }
            result shouldBe java.math.BigDecimal("99.99")
        }

        @Test
        fun `columns projection`() {
            insertUser("Alice", "alice@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .columns(users.name)
                .firstOrNull { row -> row[users.name] }
            result shouldBe "Alice"
        }
    }

    @Nested
    inner class UpdateBuilderTests {

        @Test
        fun `execute returns affected count`() {
            insertUser("Alice", "alice@test.com")
            insertUser("Bob", "bob@test.com")
            val count = UpdateBuilder(users, conn).apply {
                this[users.name] = "Updated"
                where { users.name eq "Alice" }
            }.execute()
            count shouldBe 1
        }

        @Test
        fun `update modifies values`() {
            val id = insertUser("Alice", "alice@test.com")
            UpdateBuilder(users, conn).apply {
                this[users.name] = "Alice2"
                where { users.id eq id }
            }.execute()
            val name = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.id eq id }
                .firstOrNull { row -> row[users.name] }
            name shouldBe "Alice2"
        }

        @Test
        fun `no match returns zero`() {
            val count = UpdateBuilder(users, conn).apply {
                this[users.name] = "Nobody"
                where { users.name eq "Ghost" }
            }.execute()
            count shouldBe 0
        }
    }

    @Nested
    inner class DeleteBuilderTests {

        @Test
        fun `delete with where removes matching rows`() {
            insertUser("Alice", "alice@test.com")
            insertUser("Bob", "bob@test.com")
            val count = DeleteBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "Alice" }
                .execute()
            count shouldBe 1
            SelectBuilder(users, conn, DatabaseDialect.H2).count() shouldBe 1
        }

        @Test
        fun `delete with limit removes only N rows on H2`() {
            repeat(5) { i -> insertUser("User$i", "user$i@test.com") }
            val count = DeleteBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name neq "impossible" }
                .limit(2)
                .execute()
            count shouldBe 2
            SelectBuilder(users, conn, DatabaseDialect.H2).count() shouldBe 3
        }

        @Test
        fun `executeAll removes all rows`() {
            repeat(3) { i -> insertUser("User$i", "user$i@test.com") }
            val count = DeleteBuilder(users, conn, DatabaseDialect.H2).executeAll()
            count shouldBe 3
            SelectBuilder(users, conn, DatabaseDialect.H2).count() shouldBe 0
        }
    }

    @Nested
    inner class RowTypedAccessTests {

        @Test
        fun `Row reads all column types correctly`() {
            val id = UUID.randomUUID()
            val builder = InsertBuilder(users, conn)
            builder[users.id] = id
            builder[users.name] = "TypeTest"
            builder[users.email] = "types@test.com"
            builder[users.active] = false
            builder[users.age] = 30
            builder[users.bio] = "A bio"
            builder[users.status] = TestUserStatus.INACTIVE
            builder.execute()

            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.id eq id }
                .firstOrNull { row ->
                    mapOf(
                        "id" to row[users.id],
                        "name" to row[users.name],
                        "email" to row[users.email],
                        "active" to row[users.active],
                        "age" to row[users.age],
                        "bio" to row[users.bio],
                        "status" to row[users.status],
                    )
                }
            result.shouldNotBeNull()
            result["id"] shouldBe id
            result["name"] shouldBe "TypeTest"
            result["email"] shouldBe "types@test.com"
            result["active"] shouldBe false
            result["age"] shouldBe 30
            result["bio"] shouldBe "A bio"
            result["status"] shouldBe TestUserStatus.INACTIVE
        }

        @Test
        fun `Row reads null for nullable columns`() {
            insertUser("NullTest", "null@test.com")
            val result = SelectBuilder(users, conn, DatabaseDialect.H2)
                .where { users.name eq "NullTest" }
                .firstOrNull { row -> row[users.age] to row[users.bio] }
            result.shouldNotBeNull()
            result.first.shouldBeNull()
            result.second.shouldBeNull()
        }
    }
}
