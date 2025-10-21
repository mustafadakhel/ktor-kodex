package com.mustafadakhel.kodex.observability

/**
 * Metrics collector for Kodex operations.
 *
 * Provides observability into authentication, authorization, and user management operations.
 * Applications can implement this interface to integrate with their metrics backend
 * (Prometheus, Datadog, CloudWatch, etc.) or use the provided Micrometer implementation.
 *
 * All metrics are optional - if no collector is configured, operations continue normally
 * without metrics collection.
 */
public interface KodexMetrics {

    /**
     * Records an authentication attempt.
     *
     * @param success Whether the authentication succeeded
     * @param reason Reason for failure (e.g., "invalid_credentials", "account_locked", "user_not_found")
     * @param realmId Realm where authentication occurred
     */
    public fun recordAuthentication(success: Boolean, reason: String? = null, realmId: String)

    /**
     * Records a token operation.
     *
     * @param operation Type of operation ("issue", "refresh", "revoke", "validate")
     * @param tokenType Type of token ("access", "refresh")
     * @param success Whether the operation succeeded
     * @param realmId Realm where operation occurred
     */
    public fun recordTokenOperation(
        operation: String,
        tokenType: String,
        success: Boolean,
        realmId: String
    )

    /**
     * Records an account lockout event.
     *
     * @param locked Whether account was locked (true) or unlocked (false)
     * @param realmId Realm where lockout occurred
     */
    public fun recordAccountLockout(locked: Boolean, realmId: String)

    /**
     * Records a validation failure.
     *
     * @param field Field that failed validation (e.g., "email", "password", "phone")
     * @param reason Validation failure reason
     * @param realmId Realm where validation occurred
     */
    public fun recordValidationFailure(field: String, reason: String, realmId: String)

    /**
     * Records a user operation.
     *
     * @param operation Type of operation ("create", "update", "delete", "get")
     * @param success Whether the operation succeeded
     * @param realmId Realm where operation occurred
     */
    public fun recordUserOperation(operation: String, success: Boolean, realmId: String)

    /**
     * Records database query execution time.
     *
     * @param operation Type of database operation
     * @param durationMs Duration in milliseconds
     */
    public fun recordDatabaseQuery(operation: String, durationMs: Long)
}

/**
 * No-op metrics collector that does nothing.
 * Used as default when no metrics collector is configured.
 */
public object NoOpMetrics : KodexMetrics {
    override fun recordAuthentication(success: Boolean, reason: String?, realmId: String) {}
    override fun recordTokenOperation(operation: String, tokenType: String, success: Boolean, realmId: String) {}
    override fun recordAccountLockout(locked: Boolean, realmId: String) {}
    override fun recordValidationFailure(field: String, reason: String, realmId: String) {}
    override fun recordUserOperation(operation: String, success: Boolean, realmId: String) {}
    override fun recordDatabaseQuery(operation: String, durationMs: Long) {}
}
