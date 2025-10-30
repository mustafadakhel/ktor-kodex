package com.mustafadakhel.kodex.util

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Execute a database transaction using the shared connection pool.
 *
 * Extensions can use this to access the database without managing their own connections.
 * All queries execute within the same database and can participate in the same transaction.
 *
 * Example:
 * ```kotlin
 * kodexTransaction {
 *     FailedLoginAttempts.insert {
 *         it[identifier] = email
 *         it[attemptedAt] = now(TimeZone.UTC)
 *         it[reason] = "Invalid credentials"
 *     }
 * }
 * ```
 *
 * @param statement The transaction block to execute
 * @return The result of the transaction
 */
public fun <R> kodexTransaction(statement: Transaction.() -> R): R {
    return transaction(statement = statement)
}
