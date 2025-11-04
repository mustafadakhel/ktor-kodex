package com.mustafadakhel.kodex.observability

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.*

/** Structured logging with MDC context support. */
public object KodexLogger {
    public inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)
    public fun logger(name: String): Logger = LoggerFactory.getLogger(name)
}

/** MDC keys for structured logging. */
public object LogContext {
    public const val REALM_ID: String = "realm_id"
    public const val USER_ID: String = "user_id"
    public const val SESSION_ID: String = "session_id"
    public const val OPERATION: String = "operation"
    public const val CORRELATION_ID: String = "correlation_id"
}

/** Executes block with MDC logging context. Cleans up after execution. */
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

public fun Logger.logAuthSuccess(email: String?, phone: String?, userId: UUID, realmId: String) {
    withLoggingContext(realmId = realmId, userId = userId, operation = "authenticate") {
        info("Authentication successful - identifier: ${email ?: phone}")
    }
}

public fun Logger.logAuthFailure(email: String?, phone: String?, reason: String, realmId: String) {
    withLoggingContext(realmId = realmId, operation = "authenticate") {
        warn("Authentication failed - identifier: ${email ?: phone}, reason: $reason")
    }
}

public fun Logger.logTokenOperation(operation: String, tokenType: String, success: Boolean, realmId: String) {
    withLoggingContext(realmId = realmId, operation = "token_$operation") {
        if (success) {
            info("Token $operation successful - type: $tokenType")
        } else {
            warn("Token $operation failed - type: $tokenType")
        }
    }
}

public fun Logger.logValidationFailure(field: String, reason: String, realmId: String) {
    withLoggingContext(realmId = realmId, operation = "validate_input") {
        warn("Validation failed - field: $field, reason: $reason")
    }
}
