package com.mustafadakhel.kodex.sessions

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

public fun RealmConfigScope.sessions(block: SessionConfig.() -> Unit) {
    extension(SessionConfig(), block)
}

public fun SessionConfig.anomalyDetection(block: AnomalyDetectionConfig.() -> Unit) {
    anomalyDetection.apply(block)
}

public fun SessionConfig.geoLocation(block: GeoLocationConfig.() -> Unit) {
    geoLocation.apply(block)
}
