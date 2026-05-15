package com.mustafadakhel.kodex.test

import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.schema.KodexDatabase
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Test utilities for extension modules.
 */
public class TestDatabaseSetup(private val db: KodexDatabase) {

    private val users get() = db.core.users
    private val roles get() = db.core.roles
    private val userRoles get() = db.core.userRoles

    public fun createTestUser(
        email: String,
        phone: String? = null,
        passwordHash: String = "test_hash",
        status: UserStatus = UserStatus.ACTIVE,
        realmId: String = "test-realm"
    ): UUID {
        val realm = realmId
        return db.transaction {
            users.insertAndGetId {
                it[users.passwordHash] = passwordHash
                it[users.email] = email
                if (phone != null) {
                    it[users.phoneNumber] = phone
                }
                it[users.status] = status
                it[users.realmId] = realm
            }.value
        }
    }

    public fun createRole(roleName: String, realmId: String, description: String? = null) {
        val realm = realmId
        db.transaction {
            val exists = roles.selectAll()
                .where { (roles.name eq roleName) and (roles.realmId eq realm) }
                .any()
            if (!exists) {
                roles.insert {
                    it[roles.name] = roleName
                    it[roles.realmId] = realm
                    it[roles.description] = description
                }
            }
        }
    }

    public fun assignRoleToUser(userId: UUID, roleName: String, realmId: String) {
        db.transaction {
            val roleEntityId = roles.selectAll()
                .where { (roles.name eq roleName) and (roles.realmId eq realmId) }
                .single()[roles.id]
            userRoles.insert {
                it[userRoles.userId] = EntityID(userId, users)
                it[userRoles.roleId] = roleEntityId
            }
        }
    }

    public fun createAdminUser(
        email: String = "admin@test.com",
        passwordHash: String = "test_hash",
        realmId: String = "test-realm"
    ): UUID {
        createRole("admin", realmId, "Administrator")
        val userId = createTestUser(email, passwordHash = passwordHash, realmId = realmId)
        assignRoleToUser(userId, "admin", realmId)
        return userId
    }

    public fun deleteTestUser(userId: UUID) {
        db.transaction {
            userRoles.deleteWhere { userRoles.userId eq userId }
            users.deleteWhere { users.id eq userId }
        }
    }

    public fun userHasRole(userId: UUID, roleName: String, realmId: String): Boolean {
        return db.transaction {
            userRoles.innerJoin(roles)
                .selectAll()
                .where {
                    (userRoles.userId eq userId) and
                    (roles.name eq roleName) and
                    (roles.realmId eq realmId)
                }
                .any()
        }
    }

    public fun getUserCount(): Long {
        return db.transaction {
            users.selectAll().count()
        }
    }
}
