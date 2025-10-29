package com.mustafadakhel.kodex.util

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Table

internal fun Application.connectDatabase(
    dataSource: HikariDataSource,
    extensionTables: List<Table> = emptyList()
) {
    setupExposedEngine(dataSource, extensionTables)
    monitor.subscribe(ApplicationStopping) {
        Db.clearEngine()
        dataSource.close()
    }
}

internal fun hikariDataSource(): HikariDataSource {
    val password = "changeit"

    val config = HikariConfig()
    config.username = "kodex-db"
    config.password = password
    config.driverClassName = "org.h2.Driver"
    config.jdbcUrl = "jdbc:h2:mem:kodex-db;DB_CLOSE_DELAY=-1;"
    config.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    config.isAutoCommit = false

    // Production-ready connection pool settings
    config.maximumPoolSize = 10
    config.minimumIdle = 10
    config.connectionTimeout = 30000 // 30 seconds
    config.idleTimeout = 600000 // 10 minutes
    config.maxLifetime = 1800000 // 30 minutes
    config.leakDetectionThreshold = 60000 // 1 minute (useful for development)
    config.validationTimeout = 5000 // 5 seconds

    config.validate()
    return HikariDataSource(config)
}

/**
 * Run Flyway database migrations on the provided datasource.
 *
 * This provides version-controlled database schema management for production deployments.
 * Migrations are located in `src/main/resources/db/migration` and follow the naming
 * convention `V<version>__<description>.sql`.
 *
 * Example usage:
 * ```kotlin
 * install(Kodex) {
 *     database {
 *         jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
 *         username = "user"
 *         password = "pass"
 *     }
 * }
 * // Run migrations after plugin installation
 * runFlywayMigrations(kodex.getDataSource())
 * ```
 *
 * @param dataSource The HikariDataSource to run migrations against
 * @param locations Custom migration locations (defaults to classpath:db/migration)
 * @param baselineOnMigrate Whether to baseline on migrate for existing databases
 */
public fun runFlywayMigrations(
    dataSource: HikariDataSource,
    locations: Array<String> = arrayOf("classpath:db/migration"),
    baselineOnMigrate: Boolean = true
) {
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations(*locations)
        .baselineOnMigrate(baselineOnMigrate)
        .load()

    flyway.migrate()
}