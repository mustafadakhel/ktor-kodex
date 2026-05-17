package com.mustafadakhel.kodex.jdbc

import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import javax.sql.DataSource

enum class TestUserStatus { ACTIVE, INACTIVE }

class TestUsersTable : TableDef("test_users") {
    val id = uuid("id").autoGenerate()
    val name = varchar("name", 100)
    val email = varchar("email", 255)
    val active = bool("active").default("TRUE")
    val createdAt = datetime("created_at").default("CURRENT_TIMESTAMP")
    val age = integer("age").nullable()
    val bio = text("bio").nullable()
    val status = enumByName<TestUserStatus>("status", 20).default("'ACTIVE'")

    override val primaryKey = PrimaryKeyDef(id)

    init {
        uniqueIndex(email, name = "uidx_test_users_email")
    }
}

class TestOrdersTable(users: TestUsersTable) : TableDef("test_orders") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(users.id, ReferenceAction.CASCADE)
    val amount = decimal("amount", 10, 2)
    val orderedAt = timestamp("ordered_at")

    override val primaryKey = PrimaryKeyDef(id)
}

fun createTestDataSource(dbName: String = "test_${UUID.randomUUID().toString().take(8)}"): DataSource =
    JdbcDataSource().apply { setUrl("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1") }

fun DataSource.createTestTables(users: TestUsersTable, orders: TestOrdersTable? = null) {
    connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute(users.createTableDDL(DatabaseDialect.H2))
            users.createIndexDDL(DatabaseDialect.H2).forEach { stmt.execute(it) }
            if (orders != null) {
                stmt.execute(orders.createTableDDL(DatabaseDialect.H2))
                orders.createIndexDDL(DatabaseDialect.H2).forEach { stmt.execute(it) }
            }
        }
    }
}
