package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.extension.Shutdownable
import com.mustafadakhel.kodex.extension.UserLifecycleHooks
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

public class MfaExtension internal constructor(
    private val config: MfaConfig,
    private val mfaService: MfaService,
    private val cleanupService: MfaCleanupService,
    private val sessionStore: MfaSessionStore,
    private val realmId: String
) : UserLifecycleHooks, ServiceProvider, Shutdownable {

    private val logger = LoggerFactory.getLogger(MfaExtension::class.java)

    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var cleanupJob: Job? = null

    override val priority: Int = 65

    init {
        if (config.automaticCleanup) {
            startAutomaticCleanup()
        }
    }

    override suspend fun afterAuthentication(user: AuthenticatedUser, metadata: LoginMetadata) {
        if (mfaService.isDeviceTrusted(user.userId, metadata.ipAddress, metadata.userAgent)) {
            return
        }

        val requiresMfa = config.requireMfa ||
            (config.requiredRolesForMfa.isNotEmpty() && user.roles.any { it in config.requiredRolesForMfa })

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
    override fun <T : Any> getService(type: KClass<T>): T? = when (type) {
        MfaService::class -> mfaService as T
        MfaCleanupService::class -> cleanupService as T
        else -> null
    }

    private fun startAutomaticCleanup() {
        cleanupJob = cleanupScope.launch {
            logger.info("MFA automatic cleanup started for realm '$realmId' with interval: ${config.cleanupInterval}")

            while (isActive) {
                try {
                    delay(config.cleanupInterval.inWholeMilliseconds)

                    val (challenges, sessions, devices, abandonedEnrollments) = cleanupService.cleanupAll()
                    if (challenges > 0 || sessions > 0 || devices > 0 || abandonedEnrollments > 0) {
                        logger.debug(
                            "MFA cleanup completed for realm '$realmId': " +
                            "$challenges challenges, $sessions sessions, $devices devices, " +
                            "$abandonedEnrollments abandoned enrollments removed"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
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

    override public fun shutdown() {
        stopAutomaticCleanup()
        cleanupScope.cancel()
    }
}
