package com.mustafadakhel.kodex.metrics

internal interface KodexMetrics {
    fun recordAuthentication(success: Boolean, reason: String? = null, realmId: String)
    fun recordTokenOperation(operation: String, tokenType: String, success: Boolean, realmId: String)
    fun recordAccountLockout(locked: Boolean, realmId: String)
    fun recordValidationFailure(field: String, reason: String, realmId: String)
    fun recordUserOperation(operation: String, success: Boolean, realmId: String)
    fun recordDatabaseQuery(operation: String, durationMs: Long)

    // Rate limiting metrics
    fun recordRateLimitCheck(allowed: Boolean, key: String)
    fun recordRateLimitSize(size: Int)
    fun recordRateLimitCleanup(entriesRemoved: Int)
    fun recordRateLimitEviction(entriesEvicted: Int)

    // Verification metrics
    fun recordVerificationSend(success: Boolean, contactType: String, reason: String?)
    fun recordVerificationAttempt(success: Boolean, reason: String?)

    // Password reset metrics
    fun recordPasswordResetInitiate(success: Boolean, contactType: String, reason: String?)
    fun recordPasswordResetVerify(success: Boolean, reason: String?)
    fun recordPasswordResetConsume(success: Boolean, reason: String?)

    // Token cleanup metrics
    fun recordTokenCleanup(tokenType: String, tokensRemoved: Int)
}
