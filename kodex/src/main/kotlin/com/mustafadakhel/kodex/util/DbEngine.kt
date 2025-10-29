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
    extensionTables: List<Table> = emptyList(),
    log: Boolean = false
): ExposedDbEngine {
    val existing = Db.getEngineOrNull<Transaction>() as? ExposedDbEngine?
    if (existing != null) {
        // If datasources are different, we need a new engine
        if (existing.dataSource != dataSource) {
            // Clean up the old engine only if datasource is still open
            if (existing.dataSource.isClosed.not()) {
                existing.clear()
            }
            // Fall through to create new engine below
        } else if (existing.dataSource.isClosed.not()) {
            // Same datasource and it's still open - reuse existing engine
            return existing
        }
        // If datasource is closed, fall through to create new engine
    }
    return ExposedDbEngine(dataSource, extensionTables, log).apply { Db.setEngine(this) }
}

internal class ExposedDbEngine(
    val dataSource: HikariDataSource,
    extensionTables: List<Table> = emptyList(),
    log: Boolean = false
) : DbEngine<Transaction> {
    override var runner: EngineRunner<Transaction>? = null

    init {
        setup(dataSource, extensionTables, log)
    }

    private fun setup(dataSource: HikariDataSource, extensionTables: List<Table>, log: Boolean = false) {
        val db = Database.connect(dataSource)
        runner = exposedRunner(db)

        transaction(db) {
            // Create core tables
            SchemaUtils.create(
                Users,
                Tokens,
                Roles,
                UserRoles,
                UserProfiles,
                UserCustomAttributes,
                AuditLogs
            )

            // Create extension tables
            if (extensionTables.isNotEmpty()) {
                SchemaUtils.create(*extensionTables.toTypedArray())
            }

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
