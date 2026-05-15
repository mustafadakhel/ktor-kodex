package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.extension.PersistentExtension
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.mfa.encryption.SecretEncryption
import com.mustafadakhel.kodex.mfa.schema.MfaSchema
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.ratelimit.RateLimiter
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.DatabaseAwareExtension
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
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
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

public class MfaExtension internal constructor(
    private val config: MfaConfig,
    private val timeZone: TimeZone,
    private val hashingService: HashingService,
    private val secretEncryption: SecretEncryption,
    private val eventBus: EventBus,
    private val realmId: String,
    private val rateLimiter: RateLimiter
) : UserLifecycleHooks, PersistentExtension, ServiceProvider, DatabaseAwareExtension {

    private val logger = LoggerFactory.getLogger(MfaExtension::class.java)

    private val sessionStore = MfaSessionStore(
        sessionExpiration = config.sessionExpiration,
        maxActiveSessions = config.maxActiveSessions
    )

    private lateinit var mfaService: MfaService
    private lateinit var cleanupService: MfaCleanupService

    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null

    override val priority: Int = 65

    override fun createSchema(core: CoreSchema): ExtensionSchema = MfaSchema(core)

    override fun initialize(db: KodexDatabase) {
        val schema = db.schema<MfaSchema>()

        mfaService = DefaultMfaService(
            db = db,
            schema = schema,
            config = config,
            timeZone = timeZone,
            hashingService = hashingService,
            secretEncryption = secretEncryption,
            eventBus = eventBus,
            realmId = realmId,
            rateLimiter = rateLimiter,
            sessionStore = sessionStore
        )

        cleanupService = DefaultMfaCleanupService(
            db = db,
            schema = schema,
            realmId = realmId,
            timeZone = timeZone,
            sessionStore = sessionStore,
            inactiveEnrollmentExpiration = config.inactiveEnrollmentExpiration
        )

        if (config.automaticCleanup) {
            startAutomaticCleanup()
        }
    }

    override suspend fun afterAuthentication(user: AuthenticatedUser, metadata: LoginMetadata) {
        if (mfaService.isDeviceTrusted(user.userId, metadata.ipAddress, metadata.userAgent)) {
            return
        }

        val requiresMfa = if (config.requiredRolesForMfa.isNotEmpty()) {
            user.roles.any { it in config.requiredRolesForMfa }
        } else {
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

        val session = sessionStore.createSession(
            userId = user.userId,
            ipAddress = metadata.ipAddress,
            userAgent = metadata.userAgent
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
