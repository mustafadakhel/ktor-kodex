package com.mustafadakhel.kodex.util

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Execute a database transaction using the shared connection pool.
 *
 * Extensions can use this to access the database without managing their own connections.
 * All queries execute within the same database and can participate in the same transaction.
 *
 * @param statement The transaction block to execute
 * @return The result of the transaction
 */
public fun <R> kodexTransaction(statement: Transaction.() -> R): R {
    return Db.runInEngine(statement)
}

/**
 * Execute a suspend database transaction using the shared connection pool.
 *
 * Use this version when you need to call suspend functions within the transaction block.
 * This is required for background coroutines and when calling other suspend functions.
 *
 * @param statement The suspend transaction block to execute
 * @return The result of the transaction
 */
public suspend fun <R> kodexSuspendedTransaction(statement: suspend Transaction.() -> R): R {
    val engine = Db.getEngine<Transaction>() as? ExposedDbEngine
        ?: error("No database engine registered")
    val db = engine.getDatabase()
    return newSuspendedTransaction(db = db, statement = statement)
}
