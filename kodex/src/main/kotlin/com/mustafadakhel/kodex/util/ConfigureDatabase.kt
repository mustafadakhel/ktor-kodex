package com.mustafadakhel.kodex.util

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*

internal fun Application.connectDatabase(dataSource: HikariDataSource) {
    setupExposedEngine(dataSource)
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
    config.maximumPoolSize = 3
    config.isAutoCommit = false
    config.jdbcUrl = "jdbc:h2:mem:kodex-db;DB_CLOSE_DELAY=-1;"
    config.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    config.validate()
    return HikariDataSource(config)
}