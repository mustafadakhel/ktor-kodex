package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.TokenEvent
import com.mustafadakhel.kodex.extension.*
import com.mustafadakhel.kodex.observability.KodexLogger
import com.mustafadakhel.kodex.sessions.cleanup.SessionCleanupService
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.database.SessionHistory
import com.mustafadakhel.kodex.sessions.database.Sessions
import com.mustafadakhel.kodex.sessions.device.DeviceFingerprint
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.security.AnomalyDetector
import com.mustafadakhel.kodex.sessions.security.DefaultAnomalyDetector
import com.mustafadakhel.kodex.sessions.security.DefaultGeoLocationService
import com.mustafadakhel.kodex.sessions.security.GeoLocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Table
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

public class SessionExtension(
    private val config: SessionConfig,
    private val timeZone: TimeZone,
    private val eventBus: EventBus,
    private val realmId: String
) : UserLifecycleHooks, PersistentExtension, ServiceProvider, EventSubscriberProvider {

    override val priority: Int = 100

    private val repository = SessionRepository(realmId)

    private val anomalyDetector: AnomalyDetector? = if (config.anomalyDetection.enabled) {
        DefaultAnomalyDetector(config.anomalyDetection)
    } else {
        null
    }

    private val geoLocationService: GeoLocationService? = if (config.geoLocation.enabled) {
        DefaultGeoLocationService(config.geoLocation)
    } else {
        null
    }

    private val sessionService: SessionService = DefaultSessionService(
        repository = repository,
        config = config,
        eventBus = eventBus,
        anomalyDetector = anomalyDetector,
        geoLocationService = geoLocationService
    )

    private val cleanupService = SessionCleanupService(sessionService, config)

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        cleanupService.start(cleanupScope)
    }

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> {
        return listOf(
            TokenIssuedSubscriber(realmId, sessionService, config.sessionExpiration),
            TokenRefreshedSubscriber(realmId, sessionService, config.sessionExpiration)
        )
    }

    private class TokenIssuedSubscriber(
        private val realmId: String,
        private val sessionService: SessionService,
        private val sessionExpiration: kotlin.time.Duration
    ) : EventSubscriber<TokenEvent.Issued> {
        override val eventType: KClass<out TokenEvent.Issued> = TokenEvent.Issued::class

        private val logger = KodexLogger.logger<TokenIssuedSubscriber>()

        override suspend fun onEvent(event: TokenEvent.Issued) {
            val sourceIp = event.sourceIp ?: return
            if (event.realmId != realmId) return

            try {
                val userAgent = event.userAgent
                val deviceInfo = DeviceInfo(
                    fingerprint = DeviceFingerprint.generate(sourceIp, userAgent),
                    name = DeviceFingerprint.extractDeviceName(userAgent),
                    ipAddress = sourceIp,
                    userAgent = userAgent
                )

                val expiresAt = event.timestamp + sessionExpiration

                sessionService.createSession(
                    userId = event.userId,
                    tokenFamily = event.tokenFamily,
                    deviceInfo = deviceInfo,
                    expiresAt = expiresAt
                )
            } catch (e: Exception) {
                logger.error(
                    "Failed to create session for user ${event.userId}, tokenFamily ${event.tokenFamily}: ${e.message}",
                    e
                )
            }
        }
    }

    private class TokenRefreshedSubscriber(
        private val realmId: String,
        private val sessionService: SessionService,
        private val sessionExpiration: kotlin.time.Duration
    ) : EventSubscriber<TokenEvent.Refreshed> {
        override val eventType: KClass<out TokenEvent.Refreshed> = TokenEvent.Refreshed::class

        private val logger = KodexLogger.logger<TokenRefreshedSubscriber>()

        override suspend fun onEvent(event: TokenEvent.Refreshed) {
            if (event.realmId != realmId) return

            try {
                sessionService.updateActivity(event.tokenFamily, sessionExpiration)
            } catch (e: Exception) {
                logger.error(
                    "Failed to update session activity for tokenFamily ${event.tokenFamily}: ${e.message}",
                    e
                )
            }
        }
    }

    override fun tables(): List<Table> = listOf(Sessions, SessionHistory)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? {
        return when (type) {
            SessionService::class -> sessionService as T
            else -> null
        }
    }

    override suspend fun afterAuthentication(user: AuthenticatedUser, metadata: LoginMetadata) {
        // Sessions are created via token issuance hook, not here
    }

    override suspend fun afterLogout(userId: UUID, tokenFamily: UUID?, metadata: LogoutMetadata) {
        if (tokenFamily != null) {
            val session = sessionService.getSessionByTokenFamily(tokenFamily)
            if (session != null) {
                sessionService.revokeSession(session.id, metadata.reason)
            }
        }
    }

    override suspend fun beforeUserDelete(userId: UUID) {
        sessionService.revokeAllSessions(userId)
    }

    /**
     * Stops the cleanup service and cancels the cleanup scope.
     * Should be called when the application is shutting down.
     */
    public fun shutdown() {
        cleanupService.stop()
        cleanupScope.cancel()
        geoLocationService?.close()
    }
}
