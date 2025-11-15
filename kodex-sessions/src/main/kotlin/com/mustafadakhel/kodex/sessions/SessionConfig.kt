package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.extension.RealmExtension
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

    override fun build(context: ExtensionContext): RealmExtension {
        require(maxConcurrentSessions > 0) { "maxConcurrentSessions must be positive" }
        require(sessionExpiration.isPositive()) { "sessionExpiration must be positive" }
        require(sessionHistoryRetention.isPositive()) { "sessionHistoryRetention must be positive" }
        require(cleanupInterval.isPositive()) { "cleanupInterval must be positive" }

        return SessionExtension(
            config = this,
            timeZone = context.timeZone,
            eventBus = context.eventBus,
            realmId = context.realm.owner
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
