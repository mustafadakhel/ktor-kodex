package com.mustafadakhel.kodex.sessions.security

import com.mustafadakhel.kodex.sessions.AnomalyDetectionConfig
import com.mustafadakhel.kodex.sessions.database.SessionRepository
import com.mustafadakhel.kodex.sessions.model.Session
import java.util.UUID
import kotlin.math.*

public interface AnomalyDetector {
    public suspend fun detectAnomalies(userId: UUID, newSession: Session, repository: SessionRepository): List<Anomaly>
}

public data class Anomaly(
    val type: String,
    val details: Map<String, String>
)

internal class DefaultAnomalyDetector(
    private val config: AnomalyDetectionConfig
) : AnomalyDetector {

    override suspend fun detectAnomalies(
        userId: UUID,
        newSession: Session,
        repository: SessionRepository
    ): List<Anomaly> {
        val anomalies = mutableListOf<Anomaly>()

        if (config.detectNewDevice) {
            val newDeviceAnomaly = detectNewDevice(userId, newSession, repository)
            if (newDeviceAnomaly != null) {
                anomalies.add(newDeviceAnomaly)
            }
        }

        if (config.detectNewLocation && newSession.latitude != null && newSession.longitude != null) {
            val newLocationAnomaly = detectNewLocation(userId, newSession, repository)
            if (newLocationAnomaly != null) {
                anomalies.add(newLocationAnomaly)
            }
        }

        return anomalies
    }

    private fun detectNewDevice(
        userId: UUID,
        newSession: Session,
        repository: SessionRepository
    ): Anomaly? {
        // Exclude current session to avoid false negatives
        val previousDevices = repository.findPreviousDevices(userId, excludeSessionId = newSession.id)

        return if (newSession.deviceFingerprint !in previousDevices) {
            Anomaly(
                type = "new_device",
                details = mapOf(
                    "deviceFingerprint" to newSession.deviceFingerprint,
                    "deviceName" to (newSession.deviceName ?: "Unknown"),
                    "ipAddress" to (newSession.ipAddress ?: "Unknown")
                )
            )
        } else {
            null
        }
    }

    private fun detectNewLocation(
        userId: UUID,
        newSession: Session,
        repository: SessionRepository
    ): Anomaly? {
        val newLat = newSession.latitude ?: return null
        val newLon = newSession.longitude ?: return null

        // Exclude current session to get accurate previous locations
        val previousLocations = repository.findPreviousLocations(userId, limit = 10, excludeSessionId = newSession.id)

        if (previousLocations.isEmpty()) {
            return null
        }

        val minDistance = previousLocations.minOf { (prevLat, prevLon) ->
            calculateDistance(newLat, newLon, prevLat, prevLon)
        }

        return if (minDistance > config.locationRadiusKm) {
            val nearestLocation = previousLocations.minByOrNull { (prevLat, prevLon) ->
                calculateDistance(newLat, newLon, prevLat, prevLon)
            }

            Anomaly(
                type = "new_location",
                details = mapOf(
                    "location" to (newSession.location ?: "Unknown"),
                    "distanceKm" to minDistance.toString(),
                    "previousLocation" to if (nearestLocation != null) {
                        "${nearestLocation.first},${nearestLocation.second}"
                    } else {
                        "Unknown"
                    }
                )
            )
        } else {
            null
        }
    }

    /**
     * Calculate distance between two coordinates using Haversine formula.
     * Returns distance in kilometers.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371.0 // km

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }
}
