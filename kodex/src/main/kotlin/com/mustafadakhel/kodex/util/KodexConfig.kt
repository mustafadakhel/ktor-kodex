package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.utils.io.KtorDsl
import javax.sql.DataSource
import kotlin.reflect.KClass

private const val DEFAULT_POOL_SIZE = 10
private const val DEFAULT_MIN_IDLE = 2
private const val DEFAULT_CONNECTION_TIMEOUT = 30_000L
private const val DEFAULT_IDLE_TIMEOUT = 600_000L
private const val DEFAULT_MAX_LIFETIME = 1_800_000L
private const val DEFAULT_TABLE_PREFIX = "kodex_"

@KtorDsl
public class KodexConfig internal constructor() {

    internal val realmConfigScopes: MutableList<RealmConfigScope> = mutableListOf()
    private val registeredRealmNames: MutableSet<String> = mutableSetOf()
    private var databaseConfig: DatabaseConfig? = null

    internal val autoCreateTables: Boolean
        get() = databaseConfig?.autoCreateTables ?: true

    internal val tablePrefix: String
        get() = databaseConfig?.tablePrefix ?: DEFAULT_TABLE_PREFIX

    public fun database(block: DatabaseConfigScope.() -> Unit) {
        databaseConfig = DatabaseConfigScope().apply(block).build()
    }

    public fun database(
        dataSource: DataSource,
        tablePrefix: String = DEFAULT_TABLE_PREFIX,
        autoCreateTables: Boolean = true,
    ) {
        require(tablePrefix.isNotEmpty() && tablePrefix.all { it.isLetterOrDigit() || it == '_' }) {
            "tablePrefix must be non-blank and contain only letters, digits, or underscores. Got: \"$tablePrefix\""
        }
        val dialect = DatabaseDialect.detect(dataSource)
        databaseConfig = DatabaseConfig(
            dataSource = dataSource,
            dialect = dialect,
            tablePrefix = tablePrefix,
            autoCreateTables = autoCreateTables,
            ownsDataSource = false,
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
            dataSource = config.dataSource,
            dialect = config.dialect,
            core = core,
            extensionSchemas = extensionSchemas,
            ownsDataSource = config.ownsDataSource,
        )
    }

    public fun realm(
        name: String,
        realmConfigScope: RealmConfigScope.() -> Unit,
    ) {
        require(registeredRealmNames.add(name)) { "Duplicate realm name: '$name'. Each realm must have a unique name." }
        val realm = Realm(name)
        val config = RealmConfigScope(realm).apply(realmConfigScope)
        realmConfigScopes += config
    }

    public fun realm(
        realm: Realm,
        realmConfigScope: RealmConfigScope.() -> Unit,
    ) {
        require(registeredRealmNames.add(realm.name)) { "Duplicate realm name: '${realm.name}'. Each realm must have a unique name." }
        val config = RealmConfigScope(realm).apply(realmConfigScope)
        realmConfigScopes += config
    }
}

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
        require(tablePrefix.isNotEmpty() && tablePrefix.all { it.isLetterOrDigit() || it == '_' }) {
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
        val dialect = DatabaseDialect.detect(dataSource)

        return DatabaseConfig(
            dataSource = dataSource,
            dialect = dialect,
            tablePrefix = tablePrefix,
            autoCreateTables = autoCreateTables,
            ownsDataSource = true,
        )
    }
}

internal data class DatabaseConfig(
    val dataSource: DataSource,
    val dialect: DatabaseDialect,
    val tablePrefix: String,
    val autoCreateTables: Boolean,
    val ownsDataSource: Boolean = false,
)

private fun inferDriver(url: String): String = when {
    url.startsWith("jdbc:h2:") -> "org.h2.Driver"
    url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
    else -> error(
        "Unsupported JDBC URL: \"$url\". " +
            "Kodex supports H2 (jdbc:h2:) and PostgreSQL (jdbc:postgresql:). " +
            "MySQL, MariaDB, and SQLite are not supported."
    )
}
