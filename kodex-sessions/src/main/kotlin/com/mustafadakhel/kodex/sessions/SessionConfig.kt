package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.extension.RealmExtension
import com.mustafadakhel.kodex.schema.ExtensionSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.sessions.cleanup.SessionCleanupService
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.schema.SessionSchema
import com.mustafadakhel.kodex.sessions.security.DefaultAnomalyDetector
import com.mustafadakhel.kodex.sessions.security.DefaultGeoLocationService
import com.mustafadakhel.kodex.sessions.security.GeoLocationService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

public class SessionConfig : ExtensionConfig() {
    public var maxConcurrentSessions: Int = 10
    public var sessionExpiration: Duration = 30.days
    public var sessionHistoryRetention: Duration = 90.days
    public var cleanupInterval: Duration = 1.hours
    public var anomalyDetection: AnomalyDetectionConfig = AnomalyDetectionConfig()
    public var geoLocation: GeoLocationConfig = GeoLocationConfig()

    override fun schema(tablePrefix: String): ExtensionSchema = SessionSchema(tablePrefix)

    override fun build(context: ExtensionContext, db: KodexDatabase): RealmExtension {
        require(maxConcurrentSessions > 0) { "maxConcurrentSessions must be positive" }
        require(sessionExpiration.isPositive()) { "sessionExpiration must be positive" }
        require(sessionHistoryRetention.isPositive()) { "sessionHistoryRetention must be positive" }
        require(cleanupInterval.isPositive()) { "cleanupInterval must be positive" }

        val realmId = context.realm.name
        val schema = db.schema<SessionSchema>()

        val anomalyDetector = if (anomalyDetection.enabled) {
            DefaultAnomalyDetector(anomalyDetection)
        } else {
            null
        }

        val geoLocationService: GeoLocationService? = if (geoLocation.enabled) {
            DefaultGeoLocationService(geoLocation)
        } else {
            null
        }

        val repository = SessionRepository(schema, realmId)

        val sessionService = DefaultSessionService(
            db = db,
            repository = repository,
            config = this,
            eventBus = context.eventBus,
            anomalyDetector = anomalyDetector,
            geoLocationService = geoLocationService
        )

        val cleanupService = SessionCleanupService(sessionService, this)

        return SessionExtension(
            sessionService = sessionService,
            cleanupService = cleanupService,
            geoLocationService = geoLocationService,
            sessionExpiration = sessionExpiration,
            realmId = realmId
        )
    }
}

public class AnomalyDetectionConfig {
    public var enabled: Boolean = true
    public var detectNewDevice: Boolean = true
    public var detectNewLocation: Boolean = true
    public var locationRadiusKm: Double = 100.0
}

public class GeoLocationConfig {
    public var enabled: Boolean = false
    public var provider: GeoLocationProvider = GeoLocationProvider.IP_API
    public var apiKey: String? = null
}

public enum class GeoLocationProvider {
    IP_API,
    IPINFO,
    MAXMIND
}
