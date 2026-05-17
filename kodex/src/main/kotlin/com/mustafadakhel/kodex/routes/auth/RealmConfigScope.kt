package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.extension.HookFailureStrategy
import com.mustafadakhel.kodex.extension.RealmExtension
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.extensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.ratelimit.NoOpRateLimiter
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.util.*
import io.ktor.utils.io.*
import kotlinx.datetime.TimeZone
import kotlin.reflect.KClass

internal data class RealmConfig(
    internal val realm: Realm,
    internal val secretsProvider: SecretsConfig,
    internal val claimProvider: ClaimsConfig,
    internal val tokenConfig: TokenConfig,
    internal val rolesConfig: RolesConfig,
    internal val passwordHashingConfig: PasswordHashingConfig,
    internal val tokenRotationConfig: TokenRotationConfig,
    internal val extensionConfigs: List<ExtensionConfig>,
    internal val extensionContext: ExtensionContext,
    val timeZone: TimeZone,
    val hookFailureStrategy: HookFailureStrategy,
    internal val eventBus: EventBus,
    internal val rateLimiter: RateLimiter,
) {
    /** Built lazily after the database is available. */
    internal lateinit var extensions: ExtensionRegistry
        private set

    internal fun collectSchemas(tablePrefix: String): Map<KClass<out ExtensionSchema>, ExtensionSchema> =
        extensionConfigs
            .mapNotNull { it.schema(tablePrefix) }
            .associateBy { it::class }

    internal fun buildExtensions(db: KodexDatabase) {
        val extensionsMap = mutableMapOf<KClass<out RealmExtension>, MutableList<RealmExtension>>()

        extensionConfigs.forEach { config ->
            val extension = config.build(extensionContext, db)

            if (extension is UserLifecycleHooks) {
                extensionsMap.getOrPut(UserLifecycleHooks::class) { mutableListOf() }.add(extension)
            }
            if (extension is EventSubscriberProvider) {
                extensionsMap.getOrPut(EventSubscriberProvider::class) { mutableListOf() }.add(extension)
            }
            if (extension is ServiceProvider) {
                extensionsMap.getOrPut(ServiceProvider::class) { mutableListOf() }.add(extension)
            }
        }

        extensions = ExtensionRegistry.fromLists(extensionsMap.toMap())
    }
}

@KtorDsl
/**
 * Scope used to configure a single kodex realm.
 */
public class RealmConfigScope internal constructor(
    private val realm: Realm,
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(RealmConfigScope::class.java)
    private var secretsConfigScope: SecretsConfig = SecretsConfig()
    private var claimsConfigScope: ClaimsConfig = ClaimsConfig()
    private var tokenValidityConfig: TokenConfig = TokenConfig()
    private var rolesConfig: RolesConfig = RolesConfig(realm)
    private var passwordHashingConfigScope: PasswordHashingConfigScope = PasswordHashingConfigScope()
    private var tokenRotationConfigScope: TokenRotationConfigScope = TokenRotationConfigScope()
    @PublishedApi
    internal val extensionConfigs: MutableList<Pair<ExtensionConfig, Int>> = mutableListOf()
    @PublishedApi
    internal var extensionPriorityCounter: Int = 0
    private var timeZone: TimeZone = TimeZone.currentSystemDefault()
    private var rateLimiter: RateLimiter = NoOpRateLimiter()

    public var hookFailureStrategy: HookFailureStrategy =
        HookFailureStrategy.FAIL_FAST

    @PublishedApi
    internal fun getExtensionContext(
        eventBus: EventBus
    ): ExtensionContext {
        return extensionContext(realm, timeZone, eventBus, rateLimiter)
    }

    public fun secrets(block: SecretsConfigScope.() -> Unit) {
        secretsConfigScope.block()
    }

    public fun claims(block: ClaimsConfigScope.() -> Unit) {
        claimsConfigScope.block()
    }

    public fun roles(block: RolesConfigScope.() -> Unit) {
        rolesConfig.block()
    }

    public fun tokenValidity(block: TokenConfigScope.() -> Unit) {
        tokenValidityConfig.block()
    }

    public fun passwordHashing(block: PasswordHashingConfigScope.() -> Unit) {
        passwordHashingConfigScope.apply(block)
    }

    public fun tokenRotation(block: TokenRotationConfigScope.() -> Unit) {
        tokenRotationConfigScope.apply(block)
    }

    /**
     * Extension configuration DSL.
     * Allows registering extensions with type-safe configuration.
     *
     * Note: Prefer using the extension-specific DSL functions (validation, audit, accountLockout)
     * for built-in extensions. This method is primarily for custom extensions.
     *
     * Example:
     * ```kotlin
     * // Prefer this (for built-in extensions):
     * validation {
     *     email { allowDisposable = false }
     * }
     *
     * // Use extension() for custom extensions:
     * extension(MyCustomConfig()) {
     *     customSetting = "value"
     * }
     * ```
     *
     * @param C The type of the config class
     * @param config The extension configuration instance
     * @param block Configuration block applied to the config
     */
    public inline fun <C : ExtensionConfig> extension(
        config: C,
        block: C.() -> Unit
    ) {
        val configType = config::class
        require(extensionConfigs.none { (existing, _) -> existing::class == configType }) {
            "Extension ${configType.simpleName} is already registered for this realm. " +
                "Each extension type can only be configured once per realm."
        }
        config.apply(block)
        extensionConfigs.add(config to extensionPriorityCounter++)
    }

    public fun timeZone(zone: TimeZone) {
        this.timeZone = zone
    }

    public fun rateLimiter(limiter: RateLimiter) {
        this.rateLimiter = limiter
    }

    /**
     * Finalises this scope returning an immutable [RealmConfig].
     * Extension instances are NOT built here -- they are deferred until
     * after the database is available via [RealmConfig.buildExtensions].
     */
    internal fun build(
        eventBus: EventBus
    ): RealmConfig {
        val secretsConfig = secretsConfigScope
        val claimConfig = claimsConfigScope
        val tokenValidity = tokenValidityConfig
        val passwordHashingConfig = passwordHashingConfigScope.build()
        val tokenRotationConfig = tokenRotationConfigScope.build()

        val context = getExtensionContext(eventBus)
        val configs = extensionConfigs.map { (config, _) -> config }

        if (secretsConfig.secrets().isEmpty()) throw IllegalArgumentException("Secrets must be provided")
        if (claimConfig.issuer.isNullOrBlank()) throw IllegalArgumentException("Issuer must be provided")
        if (claimConfig.audience.isNullOrBlank()) throw IllegalArgumentException("Audience must be provided")
        if (rateLimiter is NoOpRateLimiter) {
            logger.warn(
                "Realm '{}': Rate limiting is disabled (NoOpRateLimiter). " +
                    "MFA, password reset, and verification endpoints are unprotected against abuse.",
                realm.name
            )
        }
        if (hookFailureStrategy == HookFailureStrategy.SKIP_FAILED) {
            logger.warn(
                "Realm '{}': hookFailureStrategy is SKIP_FAILED. Failed lifecycle hooks " +
                    "(validation, lockout) will be silently skipped. Not recommended for production.",
                realm.name
            )
        }
        if (tokenValidity.persistenceFlags[TokenType.RefreshToken] == false) {
            throw IllegalStateException(
                "Refresh token persistence is disabled. Token refresh requires persistence. " +
                    "Either enable persist(TokenType.RefreshToken, true) or do not disable it."
            )
        }
        return RealmConfig(
            realm = realm,
            secretsProvider = secretsConfig,
            claimProvider = claimConfig,
            tokenConfig = tokenValidity,
            rolesConfig = rolesConfig,
            passwordHashingConfig = passwordHashingConfig,
            tokenRotationConfig = tokenRotationConfig,
            extensionConfigs = configs,
            extensionContext = context,
            timeZone = timeZone,
            hookFailureStrategy = hookFailureStrategy,
            eventBus = eventBus,
            rateLimiter = rateLimiter,
        )
    }
}
