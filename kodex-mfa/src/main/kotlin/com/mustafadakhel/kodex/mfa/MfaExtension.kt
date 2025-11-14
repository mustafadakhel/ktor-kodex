package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.mfa.database.MfaBackupCodes
import com.mustafadakhel.kodex.mfa.database.MfaChallenges
import com.mustafadakhel.kodex.mfa.database.MfaMethods
import com.mustafadakhel.kodex.mfa.database.MfaTotpUsedCodes
import com.mustafadakhel.kodex.mfa.database.MfaTrustedDevices
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.service.HashingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Table
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

public class MfaExtension internal constructor(
    private val config: MfaConfig,
    timeZone: TimeZone,
    hashingService: HashingService,
    secretEncryption: SecretEncryption,
    eventBus: EventBus,
    private val realmId: String,
    rateLimiter: RateLimiter
) : UserLifecycleHooks, PersistentExtension, ServiceProvider {

    private val logger = LoggerFactory.getLogger(MfaExtension::class.java)

    private val mfaService: MfaService = DefaultMfaService(
        config = config,
        timeZone = timeZone,
        hashingService = hashingService,
        secretEncryption = secretEncryption,
        eventBus = eventBus,
        realmId = realmId,
        rateLimiter = rateLimiter
    )

    private val sessionStore = MfaSessionStore(
        sessionExpiration = config.sessionExpiration,
        maxActiveSessions = config.maxActiveSessions
    )

    private val cleanupService: MfaCleanupService = DefaultMfaCleanupService(
        realmId = realmId,
        timeZone = timeZone,
        sessionStore = sessionStore,
        inactiveEnrollmentExpiration = config.inactiveEnrollmentExpiration
    )

    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null

    init {
        if (config.automaticCleanup) {
            startAutomaticCleanup()
        }
    }

    override val priority: Int = 65

    override fun tables(): List<Table> = listOf(
        MfaMethods,
        MfaChallenges,
        MfaBackupCodes,
        MfaTrustedDevices,
        MfaTotpUsedCodes
    )

    override suspend fun afterAuthentication(user: AuthenticatedUser) {
        // Check if MFA is required (global or role-based)
        val requiresMfa = if (config.requiredRolesForMfa.isNotEmpty()) {
            // Role-based enforcement
            user.roles.any { it in config.requiredRolesForMfa }
        } else {
            // Global enforcement
            config.requireMfa
        }

        if (!requiresMfa) {
            return
        }

        val methods = mfaService.getMethods(user.userId)
        if (methods.isEmpty()) {
            throw MfaThrowable.MfaEnrollmentRequired(
                "Your role requires MFA. Please enroll an MFA method."
            )
        }

        if (!mfaService.isMfaRequired(user.userId)) {
            return
        }

        // Note: Trusted device checking is not possible here because this hook lacks request context
        // (IP address, user agent). Implement trusted device checks at the route/endpoint level.

        val session = sessionStore.createSession(
            userId = user.userId,
            ipAddress = null,
            userAgent = null
        )

        throw MfaThrowable.MfaRequired(
            sessionId = session.sessionId,
            availableMethods = methods.map { it.type.name }
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? {
        return when (type) {
            MfaService::class -> mfaService as T
            MfaCleanupService::class -> cleanupService as T
            else -> null
        }
    }

    public fun getSessionStore(): MfaSessionStore = sessionStore

    public fun getCleanupService(): MfaCleanupService = cleanupService

    private fun startAutomaticCleanup() {
        cleanupJob = cleanupScope.launch {
            logger.info("MFA automatic cleanup started for realm '$realmId' with interval: ${config.cleanupInterval}")

            while (isActive) {
                try {
                    delay(config.cleanupInterval.inWholeMilliseconds)

                    val (challenges, sessions, devices) = cleanupService.cleanupAll()
                    if (challenges > 0 || sessions > 0 || devices > 0) {
                        logger.debug(
                            "MFA cleanup completed for realm '$realmId': " +
                            "$challenges challenges, $sessions sessions, $devices devices removed"
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error during MFA automatic cleanup for realm '$realmId'", e)
                }
            }
        }
    }

    public fun stopAutomaticCleanup() {
        cleanupJob?.cancel()
        cleanupJob = null
        logger.info("MFA automatic cleanup stopped for realm '$realmId'")
    }

    public fun shutdown() {
        stopAutomaticCleanup()
        cleanupScope.cancel()
    }
}
