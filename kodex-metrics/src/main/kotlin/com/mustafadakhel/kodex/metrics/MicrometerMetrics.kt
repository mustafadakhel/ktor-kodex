package com.mustafadakhel.kodex.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * Micrometer-based implementation of KodexMetrics.
 *
 * Publishes metrics to any Micrometer-supported backend (Prometheus, Datadog, CloudWatch, etc.).
 *
 * Metrics published:
 * - `kodex.authentication.attempts` (counter) - Total authentication attempts with tags: success, reason, realm
 * - `kodex.token.operations` (counter) - Token operations with tags: operation, token_type, success, realm
 * - `kodex.account.lockouts` (counter) - Account lockout events with tags: locked, realm
 * - `kodex.validation.failures` (counter) - Validation failures with tags: field, reason, realm
 * - `kodex.user.operations` (counter) - User CRUD operations with tags: operation, success, realm
 * - `kodex.database.query.time` (timer) - Database query execution time with tag: operation
 *
 * @param registry Micrometer meter registry
 */
public class MicrometerMetrics(
    private val registry: MeterRegistry
) : KodexMetrics {

    override fun recordAuthentication(success: Boolean, reason: String?, realmId: String) {
        Counter.builder("kodex.authentication.attempts")
            .tag("success", success.toString())
            .tag("reason", reason ?: "none")
            .tag("realm", realmId)
            .description("Total authentication attempts")
            .register(registry)
            .increment()
    }

    override fun recordTokenOperation(
        operation: String,
        tokenType: String,
        success: Boolean,
        realmId: String
    ) {
        Counter.builder("kodex.token.operations")
            .tag("operation", operation)
            .tag("token_type", tokenType)
            .tag("success", success.toString())
            .tag("realm", realmId)
            .description("Token operations (issue, refresh, revoke, validate)")
            .register(registry)
            .increment()
    }

    override fun recordAccountLockout(locked: Boolean, realmId: String) {
        Counter.builder("kodex.account.lockouts")
            .tag("locked", locked.toString())
            .tag("realm", realmId)
            .description("Account lockout events")
            .register(registry)
            .increment()
    }

    override fun recordValidationFailure(field: String, reason: String, realmId: String) {
        Counter.builder("kodex.validation.failures")
            .tag("field", field)
            .tag("reason", reason)
            .tag("realm", realmId)
            .description("Input validation failures")
            .register(registry)
            .increment()
    }

    override fun recordUserOperation(operation: String, success: Boolean, realmId: String) {
        Counter.builder("kodex.user.operations")
            .tag("operation", operation)
            .tag("success", success.toString())
            .tag("realm", realmId)
            .description("User CRUD operations")
            .register(registry)
            .increment()
    }

    override fun recordDatabaseQuery(operation: String, durationMs: Long) {
        Timer.builder("kodex.database.query.time")
            .tag("operation", operation)
            .description("Database query execution time")
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }
}
