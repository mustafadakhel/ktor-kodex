package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.event.DefaultEventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.JwtClaimsValidator
import com.mustafadakhel.kodex.model.JwtTokenVerifier
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.routes.auth.RealmConfig
import com.mustafadakhel.kodex.service.*
import com.mustafadakhel.kodex.token.DefaultTokenManager
import com.mustafadakhel.kodex.token.JwtTokenIssuer
import com.mustafadakhel.kodex.update.ChangeTracker
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.util.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*

/**
 * Main entry point of the kodex plugin.
 *
 * After installation the plugin exposes realm specific [RealmServices]
 * instances which handle authentication and token management.
 */
public class Kodex private constructor(
    private val realmConfigs: List<RealmConfig>,
    private val realmServices: Map<Realm, RealmServices>
) {
    public fun servicesOf(realm: Realm): RealmServices {
        return realmServices[realm] ?: throw MissingRealmServiceException(realm)
    }

    /**
     * Internal helper used by the DSL to authenticate a [Route] for multiple
     * [realms].
     */
    internal fun authenticate(realms: List<Realm>, route: Route, block: Route.() -> Unit) {
        val providerNames = realms.map { realm ->
            realmConfigs.firstOrNull { it.realm == realm }?.realm?.authProviderName
                ?: throw MissingRealmConfigException(realm)
        }.toTypedArray()

        route.authenticate(*providerNames, build = block)
    }

    public companion object Plugin : BaseApplicationPlugin<Application, KodexConfig, Kodex> {
        override val key: AttributeKey<Kodex> = AttributeKey("Kodex")


        override fun install(pipeline: Application, configure: KodexConfig.() -> Unit): Kodex {
            val kodexConfig = KodexConfig().apply(configure)
            val realmConfigs = kodexConfig.realmConfigScopes.map { it.build() }

            // Collect extension tables from all realms
            val extensionTables = realmConfigs
                .flatMap { it.extensions.getTables() }
                .distinct()

            pipeline.connectDatabase(kodexConfig.getDataSource(), extensionTables)

            val userRepository: UserRepository = databaseUserRepository()
            val databaseTokenRepository = databaseTokenRepository()

            userRepository.seedRoles(realmConfigs.flatMap { it.rolesConfig.roles })

            // Fast hasher for tokens (tokens are already high-entropy)
            val tokenHasher = saltedHashingService()

            val realmServicesMap = realmConfigs.map { realmConfig ->
                // Slow hasher for passwords (needs Argon2id)
                val passwordHasher = passwordHashingService(realmConfig.passwordHashingConfig.algorithm)

                // Create infrastructure components
                val hookExecutor = HookExecutor(realmConfig.extensions)
                val eventBus = DefaultEventBus(realmConfig.extensions)
                val changeTracker = ChangeTracker()
                val updateCommandProcessor = UpdateCommandProcessor(
                    userRepository = userRepository,
                    hookExecutor = hookExecutor,
                    changeTracker = changeTracker,
                    timeZone = realmConfig.timeZone
                )

                // Create TokenManager
                val tokenManager = DefaultTokenManager(
                    jwtTokenIssuer = JwtTokenIssuer(
                        claimsConfig = realmConfig.claimProvider,
                        secretsConfig = realmConfig.secretsProvider,
                        userRepository = userRepository,
                        realm = realmConfig.realm
                    ),
                    jwtTokenVerifier = JwtTokenVerifier(
                        claimsValidator = JwtClaimsValidator(
                            claimProvider = realmConfig.claimProvider,
                            realm = realmConfig.realm
                        ),
                        timeZone = realmConfig.timeZone,
                        tokenPersistence = realmConfig.tokenConfig.persistenceFlags,
                        tokenRepository = databaseTokenRepository,
                        hashingService = tokenHasher,
                        userRepository = userRepository
                    ),
                    tokenRepository = databaseTokenRepository,
                    userRepository = userRepository,
                    tokenValidity = realmConfig.tokenConfig.validity(),
                    hashingService = tokenHasher,
                    tokenPersistence = realmConfig.tokenConfig.persistenceFlags,
                    timeZone = realmConfig.timeZone,
                    realm = realmConfig.realm,
                    tokenRotationPolicy = realmConfig.tokenRotationConfig.policy,
                    extensions = realmConfig.extensions
                )

                // Create the 6 specialized services
                val tokenSvc = tokenService(tokenManager)
                RealmServices(
                    realm = realmConfig.realm,
                    userQuery = userQueryService(userRepository),
                    userCommand = userCommandService(
                        userRepository,
                        passwordHasher,
                        hookExecutor,
                        eventBus,
                        updateCommandProcessor,
                        realmConfig.timeZone,
                        realmConfig.realm
                    ),
                    roles = roleService(userRepository, eventBus, realmConfig.realm),
                    verification = verificationService(userRepository),
                    authentication = authenticationService(
                        userRepository,
                        passwordHasher,
                        tokenSvc,
                        hookExecutor,
                        eventBus,
                        realmConfig.timeZone,
                        realmConfig.realm
                    ),
                    tokens = tokenSvc
                )
            }.associateBy { it.realm }

            pipeline.install(Authentication) {
                realmConfigs.forEach { realmConfig ->
                    val realm = realmConfig.realm
                    realmServicesMap[realm]?.let { realmServices ->
                        bearer(realm.authProviderName) {
                            this.realm = realm.owner
                            authenticate { token ->
                                realmServices.tokens.verifyAccessToken(token.token)
                            }
                        }
                    }
                }
            }
            val plugin = Kodex(
                realmConfigs = realmConfigs,
                realmServices = realmServicesMap
            )
            return plugin
        }
    }
}

public val Application.kodex: Kodex
    get() =
        this.pluginOrNull(Kodex) ?: throw KodexNotConfiguredException()
