package com.mustafadakhel.kodex.util

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

internal interface EngineRunner<Scope> {
    fun <R> run(block: Scope.() -> R): R
}

internal fun exposedRunner(
    db: Database
): EngineRunner<Transaction> = ExposedEngineRunner(db)

private class ExposedEngineRunner(
    private val db: Database
) : EngineRunner<Transaction> {
    override fun <R> run(block: Transaction.() -> R): R = transaction(db = db, statement = block)
}
