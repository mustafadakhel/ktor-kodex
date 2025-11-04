package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.event.DefaultEventBus
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.JwtClaimsValidator
import com.mustafadakhel.kodex.model.JwtTokenVerifier
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.TokenRepository
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
 * After installation the plugin exposes realm specific [KodexRealmServices]
 * instances which handle authentication and token management.
 */
public class Kodex private constructor(
    private val realmConfigs: List<RealmConfig>,
    private val realmServices: Map<Realm, KodexRealmServices>
) {
    public fun servicesOf(realm: Realm): KodexRealmServices {
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

            val repositories = initializeRepositories()
            val realmConfigs = buildRealmConfigs(kodexConfig)

            setupDatabase(pipeline, kodexConfig, realmConfigs, repositories.userRepository)

            val tokenHasher = saltedHashingService()
            val realmServicesMap = createRealmServices(realmConfigs, repositories, tokenHasher)

            configureAuthentication(pipeline, realmConfigs, realmServicesMap)

            return Kodex(
                realmConfigs = realmConfigs,
                realmServices = realmServicesMap
            )
        }

        private data class Repositories(
            val userRepository: UserRepository,
            val tokenRepository: TokenRepository
        )

        private fun initializeRepositories(): Repositories {
            return Repositories(
                userRepository = databaseUserRepository(),
                tokenRepository = databaseTokenRepository()
            )
        }

        private fun buildRealmConfigs(kodexConfig: KodexConfig): List<RealmConfig> {
            return kodexConfig.realmConfigScopes.map { realmConfigScope ->
                val eventBus = DefaultEventBus()
                val realmConfig = realmConfigScope.build(eventBus)
                eventBus.registerExtensionSubscribers(realmConfig.extensions)
                realmConfig
            }
        }

        private fun setupDatabase(
            pipeline: Application,
            kodexConfig: KodexConfig,
            realmConfigs: List<RealmConfig>,
            userRepository: UserRepository
        ) {
            val extensionTables = realmConfigs
                .flatMap { it.extensions.getTables() }
                .distinct()

            pipeline.connectDatabase(kodexConfig.getDataSource(), extensionTables)
            userRepository.seedRoles(realmConfigs.flatMap { it.rolesConfig.roles })
        }

        private fun createRealmServices(
            realmConfigs: List<RealmConfig>,
            repositories: Repositories,
            tokenHasher: HashingService
        ): Map<Realm, KodexRealmServices> {
            return realmConfigs
                .map { realmConfig ->
                    createRealmService(realmConfig, repositories, tokenHasher)
                }
                .associateBy { it.realm }
        }

        private fun createRealmService(
            realmConfig: RealmConfig,
            repositories: Repositories,
            tokenHasher: HashingService
        ): KodexRealmServices {
            val context = ServiceContext(
                realm = realmConfig.realm,
                eventBus = realmConfig.eventBus,
                timeZone = realmConfig.timeZone,
                userRepository = repositories.userRepository,
                passwordHasher = passwordHashingService(realmConfig.passwordHashingConfig.algorithm),
                hookExecutor = HookExecutor(realmConfig.extensions)
            )

            val updateCommandProcessor = createUpdateCommandProcessor(context)
            val tokenManager = createTokenManager(realmConfig, repositories, tokenHasher)
            val tokens = tokenService(tokenManager, context.eventBus, context.realm)

            return KodexRealmServices(
                realm = context.realm,
                users = userService(context, updateCommandProcessor),
                auth = authService(context, tokens),
                tokens = tokens,
                extensions = realmConfig.extensions
            )
        }

        private fun createUpdateCommandProcessor(context: ServiceContext): UpdateCommandProcessor {
            return UpdateCommandProcessor(
                userRepository = context.userRepository,
                hookExecutor = context.hookExecutor,
                changeTracker = ChangeTracker(),
                timeZone = context.timeZone
            )
        }

        private fun createTokenManager(
            realmConfig: RealmConfig,
            repositories: Repositories,
            tokenHasher: HashingService
        ): DefaultTokenManager {
            val jwtTokenIssuer = createJwtTokenIssuer(realmConfig, repositories.userRepository)
            val jwtTokenVerifier = createJwtTokenVerifier(
                realmConfig,
                repositories,
                tokenHasher
            )

            return DefaultTokenManager(
                jwtTokenIssuer = jwtTokenIssuer,
                jwtTokenVerifier = jwtTokenVerifier,
                tokenRepository = repositories.tokenRepository,
                userRepository = repositories.userRepository,
                tokenValidity = realmConfig.tokenConfig.validity(),
                hashingService = tokenHasher,
                tokenPersistence = realmConfig.tokenConfig.persistenceFlags,
                timeZone = realmConfig.timeZone,
                realm = realmConfig.realm,
                tokenRotationPolicy = realmConfig.tokenRotationConfig.policy,
                eventBus = realmConfig.eventBus
            )
        }

        private fun createJwtTokenIssuer(
            realmConfig: RealmConfig,
            userRepository: UserRepository
        ): JwtTokenIssuer {
            return JwtTokenIssuer(
                claimsConfig = realmConfig.claimProvider,
                secretsConfig = realmConfig.secretsProvider,
                userRepository = userRepository,
                realm = realmConfig.realm
            )
        }

        private fun createJwtTokenVerifier(
            realmConfig: RealmConfig,
            repositories: Repositories,
            tokenHasher: HashingService
        ): JwtTokenVerifier {
            val claimsValidator = JwtClaimsValidator(
                claimProvider = realmConfig.claimProvider,
                realm = realmConfig.realm
            )

            return JwtTokenVerifier(
                claimsValidator = claimsValidator,
                timeZone = realmConfig.timeZone,
                tokenPersistence = realmConfig.tokenConfig.persistenceFlags,
                tokenRepository = repositories.tokenRepository,
                hashingService = tokenHasher,
                userRepository = repositories.userRepository
            )
        }

        private fun configureAuthentication(
            pipeline: Application,
            realmConfigs: List<RealmConfig>,
            realmServicesMap: Map<Realm, KodexRealmServices>
        ) {
            pipeline.install(Authentication) {
                realmConfigs.forEach { realmConfig ->
                    val realm = realmConfig.realm
                    realmServicesMap[realm]?.let { realmServices ->
                        bearer(realm.authProviderName) {
                            this.realm = realm.owner
                            authenticate { token ->
                                realmServices.tokens.verify(token.token)
                            }
                        }
                    }
                }
            }
        }
    }
}

public val Application.kodex: Kodex
    get() =
        this.pluginOrNull(Kodex) ?: throw KodexNotConfiguredException()
