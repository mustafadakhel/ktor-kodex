package com.mustafadakhel.kodex.observability

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*

/**
 * Structured logging utility for Kodex.
 *
 * Provides contextual logging with MDC (Mapped Diagnostic Context) support.
 * Automatically adds context like realm, user, operation type to log entries.
 *
 * Usage:
 * ```kotlin
 * withLoggingContext(realmId = "default", userId = userId) {
 *     logger.info("User authenticated successfully")
 * }
 * ```
 */
public object KodexLogger {
    /**
     * Gets a logger for the specified class.
     */
    public inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

    /**
     * Gets a logger with the specified name.
     */
    public fun logger(name: String): Logger = LoggerFactory.getLogger(name)
}

/**
 * MDC keys for structured logging.
 */
public object LogContext {
    public const val REALM_ID: String = "realm_id"
    public const val USER_ID: String = "user_id"
    public const val SESSION_ID: String = "session_id"
    public const val OPERATION: String = "operation"
    public const val CORRELATION_ID: String = "correlation_id"
}

/**
 * Executes a block with logging context (MDC).
 *
 * Automatically cleans up context after block execution.
 *
 * @param realmId Realm identifier
 * @param userId User identifier
 * @param sessionId Session identifier
 * @param operation Operation being performed
 * @param correlationId Request correlation ID
 * @param block Code to execute with logging context
 */
public inline fun <T> withLoggingContext(
    realmId: String? = null,
    userId: UUID? = null,
    sessionId: UUID? = null,
    operation: String? = null,
    correlationId: String? = null,
    block: () -> T
): T {
    val previousContext = mutableMapOf<String, String?>()
    val keysSet = mutableSetOf<String>()

    try {
        // Save previous context and set new values
        realmId?.let {
            keysSet.add(LogContext.REALM_ID)
            previousContext[LogContext.REALM_ID] = MDC.get(LogContext.REALM_ID)
            MDC.put(LogContext.REALM_ID, it)
        }
        userId?.let {
            keysSet.add(LogContext.USER_ID)
            previousContext[LogContext.USER_ID] = MDC.get(LogContext.USER_ID)
            MDC.put(LogContext.USER_ID, it.toString())
        }
        sessionId?.let {
            keysSet.add(LogContext.SESSION_ID)
            previousContext[LogContext.SESSION_ID] = MDC.get(LogContext.SESSION_ID)
            MDC.put(LogContext.SESSION_ID, it.toString())
        }
        operation?.let {
            keysSet.add(LogContext.OPERATION)
            previousContext[LogContext.OPERATION] = MDC.get(LogContext.OPERATION)
            MDC.put(LogContext.OPERATION, it)
        }
        correlationId?.let {
            keysSet.add(LogContext.CORRELATION_ID)
            previousContext[LogContext.CORRELATION_ID] = MDC.get(LogContext.CORRELATION_ID)
            MDC.put(LogContext.CORRELATION_ID, it)
        }

        return block()
    } finally {
        // Restore previous context only for keys that were set in this call
        keysSet.forEach { key ->
            val previousValue = previousContext[key]
            if (previousValue != null) {
                MDC.put(key, previousValue)
            } else {
                MDC.remove(key)
            }
        }
    }
}

/**
 * Logger extensions for common logging patterns.
 */

/**
 * Logs authentication success.
 */
public fun Logger.logAuthSuccess(email: String?, phone: String?, userId: UUID, realmId: String) {
    withLoggingContext(realmId = realmId, userId = userId, operation = "authenticate") {
        info("Authentication successful - identifier: ${email ?: phone}")
    }
}

/**
 * Logs authentication failure.
 */
public fun Logger.logAuthFailure(email: String?, phone: String?, reason: String, realmId: String) {
    withLoggingContext(realmId = realmId, operation = "authenticate") {
        warn("Authentication failed - identifier: ${email ?: phone}, reason: $reason")
    }
}

/**
 * Logs token operation.
 */
public fun Logger.logTokenOperation(operation: String, tokenType: String, success: Boolean, realmId: String) {
    withLoggingContext(realmId = realmId, operation = "token_$operation") {
        if (success) {
            info("Token $operation successful - type: $tokenType")
        } else {
            warn("Token $operation failed - type: $tokenType")
        }
    }
}

/**
 * Logs account lockout.
 */
public fun Logger.logAccountLockout(userId: UUID, locked: Boolean, realmId: String) {
    withLoggingContext(realmId = realmId, userId = userId, operation = if (locked) "lock_account" else "unlock_account") {
        warn("Account ${if (locked) "locked" else "unlocked"} - userId: $userId")
    }
}

/**
 * Logs validation failure.
 */
public fun Logger.logValidationFailure(field: String, reason: String, realmId: String) {
    withLoggingContext(realmId = realmId, operation = "validate_input") {
        warn("Validation failed - field: $field, reason: $reason")
    }
}
