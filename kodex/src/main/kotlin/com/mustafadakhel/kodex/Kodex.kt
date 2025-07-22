package com.mustafadakhel.kodex

import com.mustafadakhel.kodex.model.JwtClaimsValidator
import com.mustafadakhel.kodex.model.JwtTokenVerifier
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.routes.auth.RealmConfig
import com.mustafadakhel.kodex.service.KodexRealmService
import com.mustafadakhel.kodex.service.KodexService
import com.mustafadakhel.kodex.service.saltedHashingService
import com.mustafadakhel.kodex.token.DefaultTokenManager
import com.mustafadakhel.kodex.token.JwtTokenIssuer
import com.mustafadakhel.kodex.util.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*

/**
 * Main entry point of the kodex plugin.
 *
 * After installation the plugin exposes realm specific [KodexService]
 * instances which handle authentication and token management.
 */
public class Kodex private constructor(
    private val realmConfigs: List<RealmConfig>,
    private val services: Map<Realm, KodexRealmService>,
) {
    /** Returns the [KodexService] for the given [realm]. */
    public fun serviceOf(realm: Realm): KodexService {
        return services[realm] ?: throw MissingRealmServiceException(realm)
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

    /** Plugin definition used by Ktor to install [Kodex]. */
    public companion object Plugin : BaseApplicationPlugin<Application, KodexConfig, Kodex> {
        override val key: AttributeKey<Kodex> = AttributeKey("Kodex")


        override fun install(pipeline: Application, configure: KodexConfig.() -> Unit): Kodex {
            val kodexConfig = KodexConfig().apply(configure)
            val realmConfigs = kodexConfig.realmConfigScopes.map { it.build() }

            pipeline.connectDatabase(kodexConfig.dataSource)

            val userRepository: UserRepository = databaseUserRepository()
            val databaseTokenRepository = databaseTokenRepository()
            val hashingService = saltedHashingService()

            userRepository.seedRoles(realmConfigs.flatMap { it.rolesConfig.roles })
            val services = realmConfigs.map { realmConfig ->
                KodexRealmService(
                    userRepository = userRepository,
                    tokenManager = DefaultTokenManager(
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
                            hashingService = hashingService,
                            userRepository = userRepository
                        ),
                        tokenRepository = databaseTokenRepository,
                        tokenValidity = realmConfig.tokenConfig.validity(),
                        hashingService = hashingService,
                        tokenPersistence = realmConfig.tokenConfig.persistenceFlags,
                        timeZone = realmConfig.timeZone,
                        realm = realmConfig.realm
                    ),
                    realm = realmConfig.realm,
                    timeZone = realmConfig.timeZone,
                    hashingService = hashingService
                )
            }.associateBy { it.realm }

            pipeline.install(Authentication) {
                realmConfigs.forEach { realmConfig ->
                    val realm = realmConfig.realm
                    services[realm]?.let { service ->
                        bearer(realm.authProviderName) {
                            this.realm = realm.owner
                            authenticate { token ->
                                service.verifyAccessToken(token.token)
                            }
                        }
                    }
                }
            }
            val plugin = Kodex(
                realmConfigs = realmConfigs,
                services = services
            )
            return plugin
        }
    }
}

/** Accessor for the installed [Kodex] plugin. */
public val Application.kodex: Kodex
    get() =
        this.pluginOrNull(Kodex) ?: throw KodexNotConfiguredException()
