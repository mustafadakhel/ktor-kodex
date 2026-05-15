package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.event.DefaultEventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.model.JwtClaimsValidator
import com.mustafadakhel.kodex.model.JwtTokenVerifier
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.routes.auth.RealmConfig
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.DatabaseAwareExtension
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.service.KodexRealmServices
import com.mustafadakhel.kodex.service.ServiceContext
import com.mustafadakhel.kodex.service.authService
import com.mustafadakhel.kodex.service.passwordHashingService
import com.mustafadakhel.kodex.service.saltedHashingService
import com.mustafadakhel.kodex.service.tokenService
import com.mustafadakhel.kodex.service.userService
import com.mustafadakhel.kodex.token.DefaultTokenManager
import com.mustafadakhel.kodex.token.JwtTokenIssuer
import com.mustafadakhel.kodex.update.ChangeTracker
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.util.KodexConfig
import com.mustafadakhel.kodex.util.KodexNotConfiguredException
import com.mustafadakhel.kodex.util.MissingRealmConfigException
import com.mustafadakhel.kodex.util.MissingRealmServiceException
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlin.reflect.KClass

/**
 * Main entry point of the kodex plugin.
 *
 * After installation the plugin exposes realm specific [KodexRealmServices]
 * instances which handle authentication and token management.
 */
public class Kodex private constructor(
    private val realmConfigs: List<RealmConfig>,
    private val realmServices: Map<Realm, KodexRealmServices>,
    private val db: KodexDatabase,
) {
    public fun servicesOf(realm: Realm): KodexRealmServices {
        return realmServices[realm] ?: throw MissingRealmServiceException(realm)
    }

    public fun generateDDL(): List<String> = db.generateDDL()

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

            // Phase 1: Build realm configs (collect extension configs, build event buses)
            val realmConfigs = buildRealmConfigs(kodexConfig)

            // Phase 2: Collect extension schemas and build KodexDatabase
            val coreSchema = CoreSchema(kodexConfig.tablePrefix)
            val extensionSchemaMap = collectExtensionSchemas(coreSchema, realmConfigs)
            val db = kodexConfig.getKodexDatabase(coreSchema, extensionSchemaMap)

            // Phase 3: Schema creation or validation
            if (kodexConfig.autoCreateTables) {
                db.createSchema()
            } else {
                db.validateSchema()
            }

            // Phase 4: Initialize DatabaseAwareExtensions
            initializeDatabaseAwareExtensions(realmConfigs, db)

            // Phase 5: Create per-realm repositories (reused for seeding and services)
            val tokenHasher = saltedHashingService()
            val realmRepos = realmConfigs.associate { realmConfig ->
                val realmId = realmConfig.realm.name
                realmConfig to RealmRepositories(
                    userRepository = databaseUserRepository(db, realmId),
                    tokenRepository = databaseTokenRepository(db, realmId),
                )
            }

            // Phase 6: Seed roles per realm
            for ((realmConfig, repos) in realmRepos) {
                repos.userRepository.seedRoles(realmConfig.rolesConfig.roles)
            }

            // Phase 7: Build per-realm services
            val realmServicesMap = createRealmServices(realmConfigs, realmRepos, tokenHasher)

            // Phase 8: Configure authentication
            configureAuthentication(pipeline, realmConfigs, realmServicesMap)

            // Phase 9: Shutdown hooks
            val eventBuses = realmConfigs.map { it.eventBus }
            pipeline.monitor.subscribe(ApplicationStopping) {
                eventBuses.forEach { bus -> bus.shutdown() }
                db.close()
            }

            return Kodex(
                realmConfigs = realmConfigs,
                realmServices = realmServicesMap,
                db = db,
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

        private fun collectExtensionSchemas(
            coreSchema: CoreSchema,
            realmConfigs: List<RealmConfig>,
        ): Map<KClass<out ExtensionSchema>, ExtensionSchema> =
            realmConfigs
                .flatMap { it.extensions.collectSchemas(coreSchema).entries }
                .associate { it.key to it.value }

        private fun initializeDatabaseAwareExtensions(
            realmConfigs: List<RealmConfig>,
            db: KodexDatabase,
        ) {
            realmConfigs
                .flatMap { it.extensions.getAllOfType(PersistentExtension::class) }
                .filterIsInstance<DatabaseAwareExtension>()
                .forEach { it.initialize(db) }
        }

        private fun createRealmServices(
            realmConfigs: List<RealmConfig>,
            realmRepos: Map<RealmConfig, RealmRepositories>,
            tokenHasher: HashingService,
        ): Map<Realm, KodexRealmServices> =
            realmConfigs
                .map { realmConfig ->
                    val repos = realmRepos.getValue(realmConfig)
                    createRealmService(realmConfig, repos, tokenHasher)
                }
                .associateBy { it.realm }

        private fun createRealmService(
            realmConfig: RealmConfig,
            repos: RealmRepositories,
            tokenHasher: HashingService,
        ): KodexRealmServices {
            val userRepository = repos.userRepository
            val tokenRepository = repos.tokenRepository

            val context = ServiceContext(
                realm = realmConfig.realm,
                eventBus = realmConfig.eventBus,
                timeZone = realmConfig.timeZone,
                userRepository = userRepository,
                passwordHasher = passwordHashingService(realmConfig.passwordHashingConfig.algorithm),
                hookExecutor = HookExecutor(realmConfig.extensions)
            )

            val updateCommandProcessor = createUpdateCommandProcessor(context)
            val tokenManager = createTokenManager(realmConfig, userRepository, tokenRepository, tokenHasher)
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
                timeZone = context.timeZone,
                realmId = context.realm.name
            )
        }

        private fun createTokenManager(
            realmConfig: RealmConfig,
            userRepository: UserRepository,
            tokenRepository: TokenRepository,
            tokenHasher: HashingService,
        ): DefaultTokenManager {
            val jwtTokenIssuer = createJwtTokenIssuer(realmConfig, userRepository)
            val jwtTokenVerifier = createJwtTokenVerifier(
                realmConfig,
                userRepository,
                tokenRepository,
                tokenHasher,
            )

            return DefaultTokenManager(
                jwtTokenIssuer = jwtTokenIssuer,
                jwtTokenVerifier = jwtTokenVerifier,
                tokenRepository = tokenRepository,
                userRepository = userRepository,
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
            userRepository: UserRepository,
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
            userRepository: UserRepository,
            tokenRepository: TokenRepository,
            tokenHasher: HashingService,
        ): JwtTokenVerifier {
            val claimsValidator = JwtClaimsValidator(
                claimProvider = realmConfig.claimProvider,
                realm = realmConfig.realm
            )

            return JwtTokenVerifier(
                claimsValidator = claimsValidator,
                timeZone = realmConfig.timeZone,
                tokenPersistence = realmConfig.tokenConfig.persistenceFlags,
                tokenRepository = tokenRepository,
                hashingService = tokenHasher,
                userRepository = userRepository,
                realm = realmConfig.realm
            )
        }

        private fun configureAuthentication(
            pipeline: Application,
            realmConfigs: List<RealmConfig>,
            realmServicesMap: Map<Realm, KodexRealmServices>,
        ) {
            pipeline.install(Authentication) {
                realmConfigs.forEach { realmConfig ->
                    val realm = realmConfig.realm
                    realmServicesMap[realm]?.let { realmServices ->
                        bearer(realm.authProviderName) {
                            this.realm = realm.displayName
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

private class RealmRepositories(
    val userRepository: UserRepository,
    val tokenRepository: TokenRepository,
)
