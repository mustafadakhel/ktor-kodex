package com.mustafadakhel.kodex.lockout.schema

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import java.util.UUID

public class LockoutSchema(private val core: CoreSchema) : ExtensionSchema {

    public val failedLoginAttempts: FailedLoginAttemptsTable = FailedLoginAttemptsTable(core)
    public val accountLocks: AccountLocksTable = AccountLocksTable(core)

    public class FailedLoginAttemptsTable(core: CoreSchema) : Table("${core.prefix}failed_login_attempts") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val identifier: Column<String> = varchar("identifier", 255)
        public val userId: Column<UUID?> = uuid("user_id").nullable()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable()
        public val attemptedAt: Column<LocalDateTime> = datetime("attempted_at").defaultExpression(CurrentDateTime)
        public val reason: Column<String> = varchar("reason", 255)

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            index(false, realmId)
            index(false, identifier)
            index(false, userId)
            index(false, ipAddress)
            index(false, realmId, identifier, attemptedAt)
            index(false, attemptedAt)
        }
    }

    public class AccountLocksTable(core: CoreSchema) : Table("${core.prefix}account_locks") {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50)
        public val userId: Column<UUID> = uuid("user_id").index()
        public val lockedUntil: Column<LocalDateTime?> = datetime("locked_until").nullable()
        public val reason: Column<String> = varchar("reason", 255)
        public val lockedAt: Column<LocalDateTime> = datetime("locked_at").defaultExpression(CurrentDateTime)

        override val primaryKey: PrimaryKey = PrimaryKey(id)

        init {
            uniqueIndex(realmId, userId)
            index(false, realmId)
        }
    }

    private val allTables: List<Table> = listOf(failedLoginAttempts, accountLocks)

    internal fun exposedTables(): List<Table> = allTables

    override fun ddl(dialect: DatabaseDialect): List<String> =
        SchemaUtils.createStatements(*allTables.toTypedArray())

    override fun tableNames(): List<String> =
        allTables.map { it.tableName }
}
