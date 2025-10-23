package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.RealmExtension
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.extension.extensionContext
import com.mustafadakhel.kodex.model.Realm
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
    internal val extensions: ExtensionRegistry,
    val timeZone: TimeZone,
    val hookFailureStrategy: com.mustafadakhel.kodex.extension.HookFailureStrategy
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
 *         passwordHashing { /* ... */ }
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
    private var passwordHashingConfigScope: PasswordHashingConfigScope = PasswordHashingConfigScope()
    private var tokenRotationConfigScope: TokenRotationConfigScope = TokenRotationConfigScope()
    private val extensionsMap = mutableMapOf<KClass<out RealmExtension>, MutableList<RealmExtension>>()
    private var timeZone: TimeZone = TimeZone.currentSystemDefault()

    /**
     * Strategy for handling hook execution failures.
     * Default is FAIL_FAST (stop on first error).
     */
    public var hookFailureStrategy: com.mustafadakhel.kodex.extension.HookFailureStrategy =
        com.mustafadakhel.kodex.extension.HookFailureStrategy.FAIL_FAST

    /**
     * Gets the extension context for this realm configuration.
     * Used internally by extension() function to pass context to extensions.
     */
    @PublishedApi
    internal fun getExtensionContext(): com.mustafadakhel.kodex.extension.ExtensionContext {
        return extensionContext(realm, timeZone)
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
        config.apply(block)
        val context = getExtensionContext()
        val extension = config.build(context)

        // Register the extension for each hook interface it implements
        if (extension is UserLifecycleHooks) {
            @Suppress("UNCHECKED_CAST")
            registerExtension(UserLifecycleHooks::class, extension as UserLifecycleHooks)
        }
        if (extension is PersistentExtension) {
            @Suppress("UNCHECKED_CAST")
            registerExtension(PersistentExtension::class, extension as PersistentExtension)
        }
    }

    /**
     * Registers an extension with this realm.
     * Multiple extensions of the same type can be registered for chaining.
     *
     * @param extensionClass The class of the extension interface
     * @param extension The extension instance
     */
    public fun <T : RealmExtension> registerExtension(extensionClass: KClass<T>, extension: T) {
        extensionsMap.getOrPut(extensionClass) { mutableListOf() }.add(extension)
    }

    /** Configure time zone for this realm. */
    public fun timeZone(zone: TimeZone) {
        this.timeZone = zone
    }

    /**
     * Finalises this scope returning an immutable [RealmConfig].
     * Called by the plugin during installation.
     */
    internal fun build(): RealmConfig {
        val secretsConfig = secretsConfigScope
        val claimConfig = claimsConfigScope
        val tokenValidity = tokenValidityConfig
        val passwordHashingConfig = passwordHashingConfigScope.build()
        val tokenRotationConfig = tokenRotationConfigScope.build()

        // Register built-in default validation hook if no custom validation hooks registered
        if (UserLifecycleHooks::class !in extensionsMap) {
            registerExtension(UserLifecycleHooks::class, com.mustafadakhel.kodex.extension.DefaultValidationHook())
        }

        val extensionRegistry = ExtensionRegistry.fromLists(extensionsMap.toMap())
        if (secretsConfig.secrets().isEmpty()) throw IllegalArgumentException("Secrets must be provided")
        if (claimConfig.issuer.isNullOrBlank()) throw IllegalArgumentException("Issuer must be provided")
        if (claimConfig.audience.isNullOrBlank()) throw IllegalArgumentException("Audience must be provided")
        return RealmConfig(
            realm = realm,
            secretsProvider = secretsConfig,
            claimProvider = claimConfig,
            tokenConfig = tokenValidity,
            rolesConfig = rolesConfig,
            passwordHashingConfig = passwordHashingConfig,
            tokenRotationConfig = tokenRotationConfig,
            extensions = extensionRegistry,
            timeZone = timeZone,
            hookFailureStrategy = hookFailureStrategy
        )
    }
}
