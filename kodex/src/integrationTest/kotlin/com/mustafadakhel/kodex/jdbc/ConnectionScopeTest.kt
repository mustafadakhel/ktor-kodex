@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.jdbc

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class ConnectionScopeTest {

    private val users = TestUsersTable()
    private lateinit var ds: DataSource

    @BeforeEach
    fun setup() {
        ds = createTestDataSource()
        ds.createTestTables(users)
    }

    @AfterEach
    fun teardown() {
        ds.connection.use { conn ->
            conn.createStatement().execute("DROP TABLE IF EXISTS ${users.tableName}")
        }
    }

    private fun <R> withScope(block: ConnectionScope.() -> R): R {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = ConnectionScope(conn, DatabaseDialect.H2).block()
                conn.commit()
                return result
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }

    @Nested
    inner class InsertAndSelect {

        @Test
        fun `insertInto inserts a row`() {
            withScope {
                insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                select(users).count() shouldBe 1
            }
        }

        @Test
        fun `insertReturningKey returns generated UUID`() {
            val id = withScope {
                insertReturningKey(users, users.id) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Bob"
                    this[users.email] = "bob@test.com"
                }
            }
            id.shouldNotBeNull()
        }

        @Test
        fun `select where map reads inserted row`() {
            val id = UUID.randomUUID()
            withScope {
                insertInto(users) {
                    this[users.id] = id
                    this[users.name] = "Carol"
                    this[users.email] = "carol@test.com"
                }
                val name = select(users).where { users.id eq id }.firstOrNull { row -> row[users.name] }
                name shouldBe "Carol"
            }
        }
    }

    @Nested
    inner class UpdateAndDelete {

        @Test
        fun `update returns affected count`() {
            withScope {
                insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                val count = update(users) {
                    this[users.name] = "Alice2"
                    where { users.email eq "alice@test.com" }
                }
                count shouldBe 1
            }
        }

        @Test
        fun `deleteFrom where removes rows`() {
            withScope {
                insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Bob"
                    this[users.email] = "bob@test.com"
                }
                val count = deleteFrom(users).where { users.name eq "Alice" }.execute()
                count shouldBe 1
                select(users).count() shouldBe 1
            }
        }

        @Test
        fun `deleteFrom executeAll removes all`() {
            withScope {
                repeat(3) { i ->
                    insertInto(users) {
                        this[users.id] = UUID.randomUUID()
                        this[users.name] = "User$i"
                        this[users.email] = "user$i@test.com"
                    }
                }
                deleteFrom(users).executeAll()
                select(users).count() shouldBe 0
            }
        }
    }

    @Nested
    inner class BatchInsertTests {

        @Test
        fun `batchInsert inserts all items`() {
            withScope {
                val items = (1..100).map { "User$it" }
                batchInsert(users, items) { name ->
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = name
                    this[users.email] = "$name@test.com"
                }
                select(users).count() shouldBe 100
            }
        }

        @Test
        fun `batchInsert with empty list does nothing`() {
            withScope {
                batchInsert(users, emptyList<String>()) { _ -> }
                select(users).count() shouldBe 0
            }
        }
    }

    @Nested
    inner class UpsertTests {

        @Test
        fun `upsert inserts new row`() {
            withScope {
                upsert(users, listOf(users.email)) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                select(users).count() shouldBe 1
            }
        }

        @Test
        fun `upsert updates existing row on conflict`() {
            val id = UUID.randomUUID()
            withScope {
                insertInto(users) {
                    this[users.id] = id
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                upsert(users, listOf(users.email)) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice Updated"
                    this[users.email] = "alice@test.com"
                }
                select(users).count() shouldBe 1
                val name = select(users).where { users.email eq "alice@test.com" }
                    .firstOrNull { row -> row[users.name] }
                name shouldBe "Alice Updated"
            }
        }
    }

    @Nested
    inner class InsertOrIgnoreTests {

        @Test
        fun `insertOrIgnore returns 1 on new row`() {
            withScope {
                val result = insertOrIgnore(users, listOf(users.email)) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                result shouldBe 1
                select(users).count() shouldBe 1
            }
        }

        @Test
        fun `insertOrIgnore returns 0 on conflict and preserves existing row`() {
            val originalId = UUID.randomUUID()
            withScope {
                insertInto(users) {
                    this[users.id] = originalId
                    this[users.name] = "Alice"
                    this[users.email] = "alice@test.com"
                }
                val result = insertOrIgnore(users, listOf(users.email)) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice Duplicate"
                    this[users.email] = "alice@test.com"
                }
                result shouldBe 0
                select(users).count() shouldBe 1
                val name = select(users).where { users.email eq "alice@test.com" }
                    .firstOrNull { row -> row[users.name] }
                name shouldBe "Alice"
            }
        }
    }

    @Nested
    inner class ConstraintViolationTests {

        @Test
        fun `duplicate unique email detected by ConstraintViolationMapper`() {
            withScope {
                insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Alice"
                    this[users.email] = "dup@test.com"
                }
                val e = io.kotest.assertions.throwables.shouldThrow<SQLException> {
                    insertInto(users) {
                        this[users.id] = UUID.randomUUID()
                        this[users.name] = "Bob"
                        this[users.email] = "dup@test.com"
                    }
                }
                val index = ConstraintViolationMapper.detectDuplicateIndex(e, "uidx_test_users_email")
                index shouldBe "uidx_test_users_email"
            }
        }
    }

    @Nested
    inner class TransactionTests {

        @Test
        fun `commit persists data`() {
            withScope {
                insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "Committed"
                    this[users.email] = "committed@test.com"
                }
            }
            // Read in a new connection to verify persistence
            withScope {
                select(users).where { users.name eq "Committed" }.any() shouldBe true
            }
        }

        @Test
        fun `rollback discards data`() {
            ds.connection.use { conn ->
                conn.autoCommit = false
                val scope = ConnectionScope(conn, DatabaseDialect.H2)
                scope.insertInto(users) {
                    this[users.id] = UUID.randomUUID()
                    this[users.name] = "RolledBack"
                    this[users.email] = "rollback@test.com"
                }
                conn.rollback()
            }

            withScope {
                select(users).where { users.name eq "RolledBack" }.any() shouldBe false
            }
        }
    }
}
