package com.mustafadakhel.kodex.jdbc

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WhereClauseTest {

    private val table = object : TableDef("t") {
        val id = uuid("id")
        val name = varchar("name", 100)
        val age = integer("age")
        val bio = text("bio").nullable()
        override val primaryKey = PrimaryKeyDef(id)
    }

    private val other = object : TableDef("o") {
        val refId = uuid("ref_id")
        override val primaryKey = PrimaryKeyDef(refId)
    }

    private fun WhereClause.paramValues(): List<Any?> = params.map { it.value }

    @Test
    fun `eq produces correct SQL and params`() {
        val clause = table.name eq "Alice"
        clause.sql shouldBe "t.name = ?"
        clause.paramValues() shouldBe listOf("Alice")
    }

    @Test
    fun `neq produces correct SQL and params`() {
        val clause = table.name neq "Bob"
        clause.sql shouldBe "t.name <> ?"
        clause.paramValues() shouldBe listOf("Bob")
    }

    @Test
    fun `less produces correct SQL`() {
        val clause = table.age less 30
        clause.sql shouldBe "t.age < ?"
        clause.paramValues() shouldBe listOf(30)
    }

    @Test
    fun `greater produces correct SQL`() {
        val clause = table.age greater 18
        clause.sql shouldBe "t.age > ?"
        clause.paramValues() shouldBe listOf(18)
    }

    @Test
    fun `inList with values produces correct SQL`() {
        val clause = table.name inList listOf("Alice", "Bob", "Carol")
        clause.sql shouldBe "t.name IN (?, ?, ?)"
        clause.params shouldHaveSize 3
        clause.paramValues() shouldBe listOf("Alice", "Bob", "Carol")
    }

    @Test
    fun `inList with empty list produces FALSE`() {
        val clause = table.name inList emptyList()
        clause.sql shouldBe "FALSE"
        clause.params.shouldBeEmpty()
    }

    @Test
    fun `eqColumn produces column-to-column SQL with no params`() {
        val clause = table.id eqColumn other.refId
        clause.sql shouldBe "t.id = o.ref_id"
        clause.params.shouldBeEmpty()
    }

    @Test
    fun `isNull produces correct SQL`() {
        val clause = table.bio.isNull()
        clause.sql shouldBe "t.bio IS NULL"
        clause.params.shouldBeEmpty()
    }

    @Test
    fun `isNotNull produces correct SQL`() {
        val clause = table.bio.isNotNull()
        clause.sql shouldBe "t.bio IS NOT NULL"
        clause.params.shouldBeEmpty()
    }

    @Test
    fun `and combines two clauses`() {
        val clause = (table.name eq "Alice") and (table.age greater 18)
        clause.sql shouldBe "(t.name = ?) AND (t.age > ?)"
        clause.paramValues() shouldBe listOf("Alice", 18)
    }

    @Test
    fun `or combines two clauses`() {
        val clause = (table.name eq "Alice") or (table.name eq "Bob")
        clause.sql shouldBe "(t.name = ?) OR (t.name = ?)"
        clause.paramValues() shouldBe listOf("Alice", "Bob")
    }

    @Test
    fun `chained and-or produces correct nesting`() {
        val clause = (table.name eq "Alice") and (table.age greater 18) or (table.name eq "Admin")
        clause.sql shouldBe "((t.name = ?) AND (t.age > ?)) OR (t.name = ?)"
        clause.paramValues() shouldBe listOf("Alice", 18, "Admin")
    }
}
