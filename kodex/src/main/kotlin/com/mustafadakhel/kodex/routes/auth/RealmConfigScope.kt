package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.util.*
import io.ktor.utils.io.*
import kotlinx.datetime.TimeZone

/** Internal representation of a built realm configuration. */
internal data class RealmConfig(
    internal val realm: Realm,
    internal val secretsProvider: SecretsConfig,
    internal val claimProvider: ClaimsConfig,
    internal val tokenConfig: TokenConfig,
    internal val rolesConfig: RolesConfig,
    val timeZone: TimeZone
)

@KtorDsl
/**
 * Scope used to configure a single kodex realm.
 *
 * Example:
 * ```kotlin
 * kodex {
 *     realm(Realm.Main) {
 *         tokenValidity { /* ... */ }
 *     }
 * }
 * ```
 */
public class RealmConfigScope internal constructor(
    private val realm: Realm,
) {
    private var secretsConfigScope: SecretsConfig = SecretsConfig()
    private var claimsConfigScope: ClaimsConfig = ClaimsConfig()
    private var tokenValidityConfig: TokenConfig = TokenConfig()
    private var rolesConfig: RolesConfig = RolesConfig(realm)
    private var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /** Configure the secrets used to sign and verify tokens. */
    public fun secrets(block: SecretsConfigScope.() -> Unit) {
        secretsConfigScope.block()
    }

    /** Configure static claims added to issued tokens. */
    public fun claims(block: ClaimsConfigScope.() -> Unit) {
        claimsConfigScope.block()
    }

    /** Configure roles available within this realm. */
    public fun roles(block: RolesConfigScope.() -> Unit) {
        rolesConfig.block()
    }

    /** Customize token validity durations and persistence options. */
    public fun tokenValidity(block: TokenConfigScope.() -> Unit) {
        tokenValidityConfig.block()
    }

    /**
     * Finalises this scope returning an immutable [RealmConfig].
     * Called by the plugin during installation.
     */
    internal fun build(): RealmConfig {
        val secretsConfig = secretsConfigScope
        val claimConfig = claimsConfigScope
        val tokenValidity = tokenValidityConfig
        if (secretsConfig.secrets().isEmpty()) throw IllegalArgumentException("Secrets must be provided")
        if (claimConfig.issuer.isNullOrBlank()) throw IllegalArgumentException("Issuer must be provided")
        if (claimConfig.audience.isNullOrBlank()) throw IllegalArgumentException("Audience must be provided")
        return RealmConfig(
            realm = realm,
            secretsProvider = secretsConfig,
            claimProvider = claimConfig,
            tokenConfig = tokenValidity,
            rolesConfig = rolesConfig,
            timeZone = timeZone,
        )
    }
}
