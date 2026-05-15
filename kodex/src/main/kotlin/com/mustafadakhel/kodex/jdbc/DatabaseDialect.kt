package com.mustafadakhel.kodex.jdbc

import java.sql.SQLException
import javax.sql.DataSource

public enum class DatabaseDialect {
    H2,
    POSTGRESQL;

    public companion object {
        public fun detect(dataSource: DataSource): DatabaseDialect {
            try {
                dataSource.connection.use { conn ->
                    val product = conn.metaData.databaseProductName.lowercase()
                    return when {
                        "h2" in product -> H2
                        "postgre" in product -> POSTGRESQL
                        else -> throw IllegalStateException(
                            "Unsupported database: $product. Kodex supports H2 and PostgreSQL."
                        )
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException(
                    "Failed to detect database dialect. " +
                        "Verify database connectivity and that the DataSource is configured correctly. " +
                        "Cause: ${e.message}",
                    e
                )
            }
        }
    }
}
