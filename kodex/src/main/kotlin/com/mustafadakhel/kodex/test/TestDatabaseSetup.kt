package com.mustafadakhel.kodex.test

import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.Roles
import com.mustafadakhel.kodex.model.database.UserRoles
import com.mustafadakhel.kodex.model.database.Users
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.DbEngine
import com.mustafadakhel.kodex.util.EngineRunner
import com.mustafadakhel.kodex.util.exposedRunner
import com.mustafadakhel.kodex.util.kodexTransaction
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Test utilities for extension modules.
 */
public object TestDatabaseSetup {

    /**
     * Registers test database with Db singleton to ensure tests use the same code path as production.
     */
    public fun setupTestEngine(db: Database) {
        val engine = object : DbEngine<Transaction> {
            override val runner: EngineRunner<Transaction> = exposedRunner(db)
            override fun <R> run(block: Transaction.() -> R): R = runner.run(block)
            override fun clear() {}
        }
        Db.setEngine(engine)
    }

    public fun getCoreTables(): List<Table> = listOf(Users, Roles, UserRoles)

    public suspend fun createTestUser(
        email: String,
        phone: String? = null,
        passwordHash: String = "test_hash",
        status: UserStatus = UserStatus.ACTIVE,
        realmId: String = "test-realm"
    ): UUID {
        return kodexTransaction {
            Users.insertAndGetId {
                it[Users.passwordHash] = passwordHash
                it[Users.email] = email
                if (phone != null) {
                    it[Users.phoneNumber] = phone
                }
                it[Users.status] = status
                it[Users.realmId] = realmId
            }.value
        }
    }

    public suspend fun createRole(roleName: String, description: String? = null) {
        kodexTransaction {
            try {
                Roles.insert {
                    it[id] = EntityID(roleName, Roles)
                    it[Roles.description] = description
                }
            } catch (e: Exception) {
                // Ignore if role exists
            }
        }
    }

    public suspend fun assignRoleToUser(userId: UUID, roleName: String) {
        kodexTransaction {
            UserRoles.insert {
                it[UserRoles.userId] = EntityID(userId, Users)
                it[roleId] = EntityID(roleName, Roles)
            }
        }
    }

    public suspend fun createAdminUser(
        email: String = "admin@test.com",
        passwordHash: String = "test_hash"
    ): UUID {
        createRole("admin", "Administrator")
        val userId = createTestUser(email, passwordHash = passwordHash)
        assignRoleToUser(userId, "admin")
        return userId
    }

    public suspend fun deleteTestUser(userId: UUID) {
        kodexTransaction {
            UserRoles.deleteWhere { UserRoles.userId eq userId }
            Users.deleteWhere { Users.id eq userId }
        }
    }

    public suspend fun userHasRole(userId: UUID, roleName: String): Boolean {
        return kodexTransaction {
            UserRoles.selectAll()
                .where { (UserRoles.userId eq userId) and (UserRoles.roleId eq roleName) }
                .count() > 0
        }
    }

    public suspend fun getUserCount(): Long {
        return kodexTransaction {
            Users.selectAll().count()
        }
    }
}
