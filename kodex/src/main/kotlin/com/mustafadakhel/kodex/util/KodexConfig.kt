package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.utils.io.*

@KtorDsl
/**
 * Configuration for the [com.mustafadakhel.kodex.Kodex] plugin.
 *
 * Use this scope inside [Kodex.install][com.mustafadakhel.kodex.Kodex.Plugin.install]
 * to set up database access and declare realms.
 */
public class KodexConfig internal constructor() {

    internal val realmConfigScopes: MutableList<RealmConfigScope> = mutableListOf()
    private var dataSource: HikariDataSource? = null

    /**
     * Configure the JDBC connection used by the plugin.
     *
     * The provided [block] is applied to a new [HikariConfig] allowing
     * customization of the underlying HikariCP settings.
     */
    public fun database(block: HikariConfig.() -> Unit) {
        val hikariConfig = HikariConfig().apply(block)
        dataSource?.close()
        dataSource = HikariDataSource(hikariConfig)
    }

    /**
     * Returns the configured datasource, or creates a default H2 datasource
     * if none was configured by the user.
     */
    internal fun getDataSource(): HikariDataSource {
        return dataSource ?: hikariDataSource()
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
