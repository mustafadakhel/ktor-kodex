package com.mustafadakhel.kodex.observability

import com.mustafadakhel.kodex.util.exposedTransaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import javax.sql.DataSource

/**
 * Health status of a component.
 */
public enum class HealthStatus {
    /** Component is healthy and operational */
    UP,

    /** Component is degraded but still functional */
    DEGRADED,

    /** Component is down and non-functional */
    DOWN
}

/**
 * Result of a health check.
 *
 * @property status Overall health status
 * @property details Additional details about the health check
 * @property error Error message if health check failed
 */
public data class HealthCheckResult(
    val status: HealthStatus,
    val details: Map<String, Any> = emptyMap(),
    val error: String? = null
) {
    /**
     * Returns true if the component is healthy (UP or DEGRADED).
     */
    public fun isHealthy(): Boolean = status == HealthStatus.UP || status == HealthStatus.DEGRADED
}

/**
 * Health check for Kodex services.
 *
 * Provides health status indicators for monitoring and alerting.
 * Can be integrated with Kubernetes liveness/readiness probes, load balancer health checks,
 * or monitoring systems.
 */
public object KodexHealth {

    /**
     * Checks database connectivity.
     *
     * Attempts a simple query to verify database is accessible and responsive.
     *
     * @return Health check result with database status
     */
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

    /**
     * Checks overall Kodex health.
     *
     * Aggregates health checks from all components and returns overall status.
     *
     * @return Overall health check result
     */
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
