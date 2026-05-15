package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.utils.io.KtorDsl
import org.jetbrains.exposed.sql.Database
import kotlin.reflect.KClass

private const val DEFAULT_POOL_SIZE = 10
private const val DEFAULT_MIN_IDLE = 2
private const val DEFAULT_CONNECTION_TIMEOUT = 30_000L
private const val DEFAULT_IDLE_TIMEOUT = 600_000L
private const val DEFAULT_MAX_LIFETIME = 1_800_000L
private const val DEFAULT_TABLE_PREFIX = "kodex_"

private val TABLE_PREFIX_PATTERN = Regex("[a-zA-Z0-9_]+")

/**
 * Configuration for the [com.mustafadakhel.kodex.Kodex] plugin.
 *
 * Use this scope inside [Kodex.install][com.mustafadakhel.kodex.Kodex.Plugin.install]
 * to set up database access and declare realms.
 */
@KtorDsl
public class KodexConfig internal constructor() {

    internal val realmConfigScopes: MutableList<RealmConfigScope> = mutableListOf()
    private var databaseConfig: DatabaseConfig? = null

    internal val autoCreateTables: Boolean
        get() = databaseConfig?.autoCreateTables ?: true

    internal val tablePrefix: String
        get() = databaseConfig?.tablePrefix ?: DEFAULT_TABLE_PREFIX

    /**
     * Configure a new database connection managed by Kodex.
     *
     * A HikariCP pool is created from the provided settings.
     * At minimum, [DatabaseConfigScope.jdbcUrl] must be set.
     */
    public fun database(block: DatabaseConfigScope.() -> Unit) {
        databaseConfig = DatabaseConfigScope().apply(block).build()
    }

    /**
     * Use an existing Exposed [Database] instance.
     *
     * Kodex will not manage the connection lifecycle; the caller
     * is responsible for closing the database when appropriate.
     */
    public fun database(
        db: Database,
        tablePrefix: String = DEFAULT_TABLE_PREFIX,
        block: DatabaseOptionsScope.() -> Unit = {}
    ) {
        val options = DatabaseOptionsScope().apply(block)
        databaseConfig = DatabaseConfig(
            database = db,
            tablePrefix = tablePrefix,
            autoCreateTables = options.autoCreateTables,
        )
    }

    internal fun getKodexDatabase(
        core: CoreSchema,
        extensionSchemas: Map<KClass<out ExtensionSchema>, ExtensionSchema>,
    ): KodexDatabase {
        val config = requireNotNull(databaseConfig) {
            "Kodex database not configured. Add a database { } block to install(Kodex) { }."
        }
        return KodexDatabase(
            database = config.database,
            core = core,
            extensionSchemas = extensionSchemas,
            ownsDataSource = config.ownsDataSource,
            dataSource = config.dataSource,
        )
    }

    /**
     * Declare a new realm identified by [name].
     */
    public fun realm(
        name: String,
        realmConfigScope: RealmConfigScope.() -> Unit
    ) {
        val realm = Realm(name)
        val config = RealmConfigScope(realm).apply(realmConfigScope)
        realmConfigScopes += config
    }

    /**
     * Declare a new realm using an existing [Realm] value.
     */
    public fun realm(
        realm: Realm,
        realmConfigScope: RealmConfigScope.() -> Unit
    ) {
        val config = RealmConfigScope(realm).apply(realmConfigScope)
        realmConfigScopes += config
    }
}

/**
 * DSL scope for configuring a new database connection via HikariCP.
 */
public class DatabaseConfigScope internal constructor() {
    public var jdbcUrl: String? = null
    public var driverClassName: String? = null
    public var username: String = ""
    public var password: String = ""
    public var tablePrefix: String = DEFAULT_TABLE_PREFIX
    public var autoCreateTables: Boolean = true

    public var maximumPoolSize: Int = DEFAULT_POOL_SIZE
    public var minimumIdle: Int = DEFAULT_MIN_IDLE
    public var connectionTimeout: Long = DEFAULT_CONNECTION_TIMEOUT
    public var idleTimeout: Long = DEFAULT_IDLE_TIMEOUT
    public var maxLifetime: Long = DEFAULT_MAX_LIFETIME

    internal fun build(): DatabaseConfig {
        val url = requireNotNull(jdbcUrl) {
            "jdbcUrl is required in database { } configuration."
        }
        require(tablePrefix.isNotBlank() && TABLE_PREFIX_PATTERN.matches(tablePrefix)) {
            "tablePrefix must be non-blank and contain only letters, digits, or underscores. Got: \"$tablePrefix\""
        }

        val driver = driverClassName ?: inferDriver(url)
        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = url
            this.driverClassName = driver
            this.username = this@DatabaseConfigScope.username
            this.password = this@DatabaseConfigScope.password
            this.maximumPoolSize = this@DatabaseConfigScope.maximumPoolSize
            this.minimumIdle = this@DatabaseConfigScope.minimumIdle
            this.connectionTimeout = this@DatabaseConfigScope.connectionTimeout
            this.idleTimeout = this@DatabaseConfigScope.idleTimeout
            this.maxLifetime = this@DatabaseConfigScope.maxLifetime
            this.isAutoCommit = false
        }
        val dataSource = HikariDataSource(hikariConfig)
        val database = Database.connect(dataSource)

        return DatabaseConfig(
            database = database,
            tablePrefix = tablePrefix,
            autoCreateTables = autoCreateTables,
            ownsDataSource = true,
            dataSource = dataSource,
        )
    }
}

/**
 * DSL scope for options when using an existing Exposed [Database].
 */
public class DatabaseOptionsScope internal constructor() {
    public var autoCreateTables: Boolean = true
}

internal data class DatabaseConfig(
    val database: Database,
    val tablePrefix: String,
    val autoCreateTables: Boolean,
    val ownsDataSource: Boolean = false,
    val dataSource: HikariDataSource? = null,
)

private fun inferDriver(url: String): String = when {
    url.startsWith("jdbc:h2:") -> "org.h2.Driver"
    url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
    url.startsWith("jdbc:mysql:") -> "com.mysql.cj.jdbc.Driver"
    url.startsWith("jdbc:mariadb:") -> "org.mariadb.jdbc.Driver"
    url.startsWith("jdbc:sqlite:") -> "org.sqlite.JDBC"
    else -> error(
        "Cannot infer JDBC driver for URL \"$url\". " +
            "Set driverClassName explicitly in database { }."
    )
}
