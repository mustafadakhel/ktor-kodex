package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.throwable.KodexThrowable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Transaction
import java.sql.SQLException

internal fun <R> exposedTransaction(
    statement: Transaction.() -> R,
) = kodexTransaction(statement, exceptionMapper = { exposedSqlExceptionMapper(it, ::defaultExceptionMapper) })

internal inline fun <R, reified Scope> kodexTransaction(
    crossinline statement: Scope.() -> R,
    crossinline exceptionMapper: (Throwable) -> Unit
): R = exceptionMapperTransaction(
    statement = statement,
    exceptionMapper = exceptionMapper
)

internal inline fun <R, reified Scope> exceptionMapperTransaction(
    crossinline statement: Scope.() -> R,
    crossinline exceptionMapper: (Throwable) -> Unit
) = Db.runInEngine<Scope, R> {
    runCatching { statement() }
        .onFailure(exceptionMapper)
        .getOrThrow()
}

internal fun exposedSqlExceptionMapper(
    e: Throwable,
    exceptionMapper: (ExposedSQLException) -> Unit
) {
    if (e is ExposedSQLException) {
        exceptionMapper(e)
    } else {
        throw e
    }
}

internal fun <T : SQLException> defaultExceptionMapper(e: T) {
    when {
        // SQL state '23' indicates integrity constraint violation (JDBC standard)
        e.sqlState?.startsWith("23") == true -> {
            throw KodexThrowable.Database.Unknown("Data integrity constraint violation", e)
        }
        // SQL state '08' indicates connection issues
        e.sqlState?.startsWith("08") == true -> {
            throw KodexThrowable.Database.Unknown("Database connection error", e)
        }
        // SQL state '42' indicates syntax error or access rule violation
        e.sqlState?.startsWith("42") == true -> {
            throw KodexThrowable.Database.Unknown("Database access error", e)
        }

        else -> throw KodexThrowable.Database.Unknown(e.message, e)
    }
}
