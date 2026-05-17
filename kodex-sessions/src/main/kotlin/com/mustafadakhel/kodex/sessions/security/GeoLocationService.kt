package com.mustafadakhel.kodex.sessions.security

import com.mustafadakhel.kodex.sessions.GeoLocationConfig
import com.mustafadakhel.kodex.sessions.GeoLocationProvider
import com.mustafadakhel.kodex.sessions.model.GeoLocation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable

public interface GeoLocationService : Closeable {
    public suspend fun resolveLocation(ipAddress: String): GeoLocation?

    override fun close() {}
}

internal class DefaultGeoLocationService(
    private val config: GeoLocationConfig
) : GeoLocationService {

    private val logger = LoggerFactory.getLogger(DefaultGeoLocationService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    override suspend fun resolveLocation(ipAddress: String): GeoLocation? {
        if (!config.enabled) return null

        return try {
            when (config.provider) {
                GeoLocationProvider.IP_API -> resolveViaIpApi(ipAddress)
                GeoLocationProvider.IPINFO -> resolveViaIpInfo(ipAddress)
                GeoLocationProvider.MAXMIND -> null // Not implemented
            }
        } catch (e: Exception) {
            logger.error(
                "Geolocation resolution failed for IP {} using provider {}: {}",
                ipAddress,
                config.provider,
                e.message
            )
            null
        }
    }

    private suspend fun resolveViaIpApi(ipAddress: String): GeoLocation? {
        val response = client.get("https://ip-api.com/json/$ipAddress") {
            parameter("fields", "status,country,city,lat,lon")
        }.body<IpApiResponse>()

        return if (response.status == "success") {
            GeoLocation(
                city = response.city,
                country = response.country,
                latitude = response.lat,
                longitude = response.lon
            )
        } else {
            null
        }
    }

    private suspend fun resolveViaIpInfo(ipAddress: String): GeoLocation? {
        val token = config.apiKey ?: return null

        val response = client.get("https://ipinfo.io/$ipAddress/json") {
            parameter("token", token)
        }.body<IpInfoResponse>()

        // Safe parsing of latitude/longitude coordinates
        val coordinates = response.loc?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null
        if (coordinates.size != 2) return null

        val (lat, lon) = coordinates

        return GeoLocation(
            city = response.city,
            country = response.country,
            latitude = lat,
            longitude = lon
        )
    }

    override fun close() {
        client.close()
    }

    @Serializable
    private data class IpApiResponse(
        val status: String,
        val country: String = "",
        val city: String? = null,
        val lat: Double = 0.0,
        val lon: Double = 0.0
    )

    @Serializable
    private data class IpInfoResponse(
        val city: String? = null,
        val country: String = "",
        val loc: String? = null
    )
}
