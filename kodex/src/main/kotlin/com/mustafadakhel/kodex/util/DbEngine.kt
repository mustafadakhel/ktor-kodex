package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.database.*
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

internal interface DbEngine<Scope> {
    val runner: EngineRunner<Scope>?

    fun <R> run(block: Scope.() -> R): R
    fun clear()
}

internal fun setupExposedEngine(
    dataSource: HikariDataSource,
    log: Boolean = false
): ExposedDbEngine {
    val existing = Db.getEngineOrNull<Transaction>() as? ExposedDbEngine?
    if (existing != null) {
        if (existing.dataSource != dataSource)
            existing.dataSource.close()
        if (existing.dataSource.isClosed.not()) {
            return existing
        }
        existing.clear()
    }
    return ExposedDbEngine(dataSource, log).apply { Db.setEngine(this) }
}

internal class ExposedDbEngine(
    val dataSource: HikariDataSource,
    log: Boolean = false
) : DbEngine<Transaction> {
    override var runner: EngineRunner<Transaction>? = null

    init {
        setup(dataSource, log)
    }

    private fun setup(dataSource: HikariDataSource, log: Boolean = false) {
        val db = Database.connect(dataSource)
        runner = exposedRunner(db)

        transaction(db) {
            SchemaUtils.create(
                Users,
                Tokens,
                Roles,
                UserRoles,
                UserProfiles,
                UserCustomAttributes,
                FailedLoginAttempts,
                AccountLockouts,
                AuditLogs
            )
            if (log) {
                addLogger(StdOutSqlLogger)
            }
        }

    }

    override fun clear() {
        runner = null
        transaction {
            SchemaUtils.drop(
                Users,
                Tokens,
                Roles,
                UserRoles,
                UserProfiles,
                UserCustomAttributes,
                FailedLoginAttempts,
                AccountLockouts,
                AuditLogs
            )
        }
        dataSource.takeIf { it.isClosed.not() }?.close()
    }

    override fun <R> run(block: Transaction.() -> R): R {
        val runner = this.runner
            ?: throw IllegalStateException("Database engine is not initialized. Call Db.engine() first.")
        return runner.run(block)
    }
}
