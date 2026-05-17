package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.TokenEvent
import com.mustafadakhel.kodex.extension.*
import com.mustafadakhel.kodex.observability.KodexLogger
import com.mustafadakhel.kodex.sessions.cleanup.SessionCleanupService
import com.mustafadakhel.kodex.sessions.device.DeviceFingerprint
import com.mustafadakhel.kodex.sessions.model.DeviceInfo
import com.mustafadakhel.kodex.sessions.security.GeoLocationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID
import kotlin.reflect.KClass

public class SessionExtension internal constructor(
    private val sessionService: SessionService,
    private val cleanupService: SessionCleanupService,
    private val geoLocationService: GeoLocationService?,
    private val sessionExpiration: kotlin.time.Duration,
    private val realmId: String
) : UserLifecycleHooks, ServiceProvider, EventSubscriberProvider, Shutdownable {

    override val priority: Int = 100

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        cleanupService.start(cleanupScope)
    }

    override fun getEventSubscribers(): List<EventSubscriber<out KodexEvent>> = listOf(
        TokenIssuedSubscriber(realmId, sessionService, sessionExpiration),
        TokenRefreshedSubscriber(realmId, sessionService, sessionExpiration),
        TokenRevokedSubscriber(realmId, sessionService)
    )

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

    private class TokenRevokedSubscriber(
        private val realmId: String,
        private val sessionService: SessionService
    ) : EventSubscriber<TokenEvent.Revoked> {
        override val eventType: KClass<out TokenEvent.Revoked> = TokenEvent.Revoked::class

        private val logger = KodexLogger.logger<TokenRevokedSubscriber>()

        override suspend fun onEvent(event: TokenEvent.Revoked) {
            if (event.realmId != realmId) return

            try {
                sessionService.revokeAllSessions(event.userId)
            } catch (e: Exception) {
                logger.error(
                    "Failed to revoke sessions for user ${event.userId} after token revocation: ${e.message}",
                    e
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: KClass<T>): T? = when (type) {
        SessionService::class -> sessionService as T
        else -> null
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
    override public fun shutdown() {
        cleanupService.stop()
        cleanupScope.cancel()
        geoLocationService?.close()
    }
}
