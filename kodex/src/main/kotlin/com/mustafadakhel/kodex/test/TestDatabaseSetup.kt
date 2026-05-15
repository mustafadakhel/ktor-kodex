package com.mustafadakhel.kodex.test

import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.eqColumn
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.schema.KodexDatabase
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
            insertReturningKey(users, users.id) {
                set(users.passwordHash, passwordHash)
                set(users.email, email)
                if (phone != null) {
                    set(users.phoneNumber, phone)
                }
                set(users.status, status)
                set(users.realmId, realm)
            }
        }
    }

    public fun createRole(roleName: String, realmId: String, description: String? = null) {
        val realm = realmId
        db.transaction {
            val exists = select(roles)
                .where { (roles.name eq roleName) and (roles.realmId eq realm) }
                .any()
            if (!exists) {
                insertInto(roles) {
                    set(roles.name, roleName)
                    set(roles.realmId, realm)
                    set(roles.description, description)
                }
            }
        }
    }

    public fun assignRoleToUser(userId: UUID, roleName: String, realmId: String) {
        db.transaction {
            val roleId = select(roles)
                .where { (roles.name eq roleName) and (roles.realmId eq realmId) }
                .singleOrNull { it[roles.id] }
                ?: error("Role '$roleName' not found in realm '$realmId'")
            insertInto(userRoles) {
                set(userRoles.userId, userId)
                set(userRoles.roleId, roleId)
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
            deleteFrom(userRoles).where { userRoles.userId eq userId }.execute()
            deleteFrom(users).where { users.id eq userId }.execute()
        }
    }

    public fun userHasRole(userId: UUID, roleName: String, realmId: String): Boolean {
        return db.transaction {
            select(userRoles)
                .innerJoin(roles) { userRoles.roleId eqColumn roles.id }
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
            select(users).count()
        }
    }
}
