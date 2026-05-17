package com.mustafadakhel.kodex.lockout.schema

import com.mustafadakhel.kodex.jdbc.Column
import com.mustafadakhel.kodex.jdbc.CoreTable
import com.mustafadakhel.kodex.jdbc.PrimaryKeyDef
import com.mustafadakhel.kodex.jdbc.ReferenceAction
import com.mustafadakhel.kodex.jdbc.TableDef
import com.mustafadakhel.kodex.schema.ExtensionSchema
import kotlinx.datetime.LocalDateTime
import java.util.UUID

public class LockoutSchema(private val prefix: String) : ExtensionSchema {

    public val failedLoginAttempts: FailedLoginAttemptsTable = FailedLoginAttemptsTable(prefix)
    public val accountLocks: AccountLocksTable = AccountLocksTable(prefix)

    public class FailedLoginAttemptsTable(prefix: String) : TableDef("${prefix}failed_login_attempts", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50).index()
        public val identifier: Column<String> = varchar("identifier", 255).index()
        public val userId: Column<UUID?> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).nullable().index()
        public val ipAddress: Column<String?> = varchar("ip_address", 45).nullable().index()
        public val attemptedAt: Column<LocalDateTime> = datetime("attempted_at").default("CURRENT_TIMESTAMP").index()
        public val reason: Column<String> = varchar("reason", 255)

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            index(realmId, identifier, attemptedAt)
        }
    }

    public class AccountLocksTable(prefix: String) : TableDef("${prefix}account_locks", prefix) {
        public val id: Column<UUID> = uuid("id").autoGenerate()
        public val realmId: Column<String> = varchar("realm_id", 50).index()
        public val userId: Column<UUID> = uuid("user_id").references(CoreTable.Users, ReferenceAction.CASCADE).index()
        public val lockedUntil: Column<LocalDateTime?> = datetime("locked_until").nullable()
        public val reason: Column<String> = varchar("reason", 255)
        public val lockedAt: Column<LocalDateTime> = datetime("locked_at").default("CURRENT_TIMESTAMP")

        override val primaryKey: PrimaryKeyDef = PrimaryKeyDef(id)

        init {
            uniqueIndex(realmId, userId)
        }
    }

    override fun tables(): List<TableDef> = listOf(failedLoginAttempts, accountLocks)
}
