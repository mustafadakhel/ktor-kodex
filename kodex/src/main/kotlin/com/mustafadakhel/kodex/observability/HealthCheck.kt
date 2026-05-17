@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.observability

import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.schema.KodexDatabase
import java.sql.SQLException
import kotlin.time.measureTimedValue

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
    public val isHealthy: Boolean get() = status == HealthStatus.UP || status == HealthStatus.DEGRADED
}

public class KodexHealth(private val db: KodexDatabase) {
    public fun checkDatabase(): HealthCheckResult {
        return try {
            val (_, duration) = measureTimedValue {
                db.transaction {
                    conn.prepareStatement("SELECT 1").use { ps -> ps.executeQuery().use { } }
                }
            }

            HealthCheckResult(
                status = HealthStatus.UP,
                details = mapOf(
                    "responseTimeMs" to duration.inWholeMilliseconds,
                    "description" to "Database connection successful"
                )
            )
        } catch (e: SQLException) {
            HealthCheckResult(
                status = HealthStatus.DOWN,
                error = "Database connection failed: ${e.message}",
                details = mapOf(
                    "exception" to (e::class.simpleName ?: "Unknown")
                )
            )
        }
    }

    public fun checkOverall(): HealthCheckResult {
        val databaseHealth = checkDatabase()

        val allHealthy = databaseHealth.isHealthy

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
