package com.mustafadakhel.kodex.metrics

internal interface KodexMetrics {
    fun recordAuthentication(success: Boolean, reason: String? = null, realmId: String)
    fun recordTokenOperation(operation: String, tokenType: String, success: Boolean, realmId: String)
    fun recordAccountLockout(locked: Boolean, realmId: String)
    fun recordValidationFailure(field: String, reason: String, realmId: String)
    fun recordUserOperation(operation: String, success: Boolean, realmId: String)
    fun recordDatabaseQuery(operation: String, durationMs: Long)
}
