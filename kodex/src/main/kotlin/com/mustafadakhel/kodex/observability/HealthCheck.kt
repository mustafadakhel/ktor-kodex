package com.mustafadakhel.kodex.observability

import com.mustafadakhel.kodex.util.exposedTransaction

public enum class HealthStatus {
    UP,
    DEGRADED,
    DOWN
}

public data class HealthCheckResult(
    val status: HealthStatus,
    val details: Map<String, Any> = emptyMap(),
    val error: String? = null
) {
    public fun isHealthy(): Boolean = status == HealthStatus.UP || status == HealthStatus.DEGRADED
}

/** Health check for Kodex services. */
public object KodexHealth {

    /** Checks database connectivity. */
    public fun checkDatabase(): HealthCheckResult {
        return try {
            // Simple query to check database connectivity
            val startTime = System.currentTimeMillis()
            exposedTransaction {
                connection.prepareStatement("SELECT 1", false).executeQuery()
            }
            val responseTime = System.currentTimeMillis() - startTime

            HealthCheckResult(
                status = HealthStatus.UP,
                details = mapOf(
                    "responseTimeMs" to responseTime,
                    "description" to "Database connection successful"
                )
            )
        } catch (e: Exception) {
            HealthCheckResult(
                status = HealthStatus.DOWN,
                error = "Database connection failed: ${e.message}",
                details = mapOf(
                    "exception" to e.javaClass.simpleName
                )
            )
        }
    }

    /** Checks overall system health. */
    public fun checkOverall(): HealthCheckResult {
        val databaseHealth = checkDatabase()

        val allHealthy = databaseHealth.isHealthy()

        return when {
            allHealthy -> HealthCheckResult(
                status = HealthStatus.UP,
                details = mapOf(
                    "database" to databaseHealth.status.name,
                    "description" to "All systems operational"
                )
            )

            else -> HealthCheckResult(
                status = HealthStatus.DOWN,
                error = "One or more components are down",
                details = mapOf(
                    "database" to databaseHealth.status.name
                )
            )
        }
    }
}
