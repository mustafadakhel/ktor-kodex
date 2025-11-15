package com.mustafadakhel.kodex.sessions.routes

import com.mustafadakhel.kodex.extension.ServiceProvider
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.routes.auth.kodex
import com.mustafadakhel.kodex.sessions.SessionService
import com.mustafadakhel.kodex.sessions.model.SessionEndReason
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Extension function to register session management routes.
 */
public fun Route.sessionRoutes(realmId: String, serviceProvider: ServiceProvider) {
    val sessionService = serviceProvider.getService(SessionService::class)
        ?: error("SessionService not available")

    route("/sessions") {
        // GET /sessions - List active sessions for the authenticated user
        get {
            val principal = call.kodex
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

            val sessions = sessionService.listActiveSessions(principal.userId)

            // Determine current session using tokenFamily from JWT claims
            val currentTokenFamily = principal.tokenFamily

            call.respond(SessionListResponse(
                sessions = sessions.map { session ->
                    SessionDto(
                        id = session.id.toString(),
                        deviceName = session.deviceName,
                        ipAddress = session.ipAddress,
                        location = session.location,
                        createdAt = session.createdAt.toString(),
                        lastActivityAt = session.lastActivityAt.toString(),
                        isCurrent = session.tokenFamily == currentTokenFamily
                    )
                }
            ))
        }

        // GET /sessions/history - Get session history for the authenticated user (paginated)
        get("/history") {
            val principal = call.kodex
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

            val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

            val historyPage = sessionService.getSessionHistoryPage(principal.userId, limit, offset)

            call.respond(SessionHistoryPageResponse(
                history = historyPage.entries.map { entry ->
                    SessionHistoryDto(
                        id = entry.id.toString(),
                        sessionId = entry.sessionId.toString(),
                        deviceName = entry.deviceName,
                        ipAddress = entry.ipAddress,
                        location = entry.location,
                        loginAt = entry.loginAt.toString(),
                        logoutAt = entry.logoutAt?.toString(),
                        endReason = entry.endReason
                    )
                },
                totalCount = historyPage.totalCount,
                offset = historyPage.offset,
                limit = historyPage.limit,
                hasMore = historyPage.hasMore
            ))
        }

        // DELETE /sessions/:sessionId - Revoke a specific session
        delete("/{sessionId}") {
            val principal = call.kodex
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

            val sessionId = call.parameters["sessionId"]?.let { UUID.fromString(it) }
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sessionId required"))

            // Verify the session belongs to the authenticated user
            val session = sessionService.getSession(sessionId)
            if (session == null) {
                return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            }
            if (session.userId != principal.userId) {
                return@delete call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
            }

            val reason = call.parameters["reason"] ?: SessionEndReason.USER_REVOKED
            sessionService.revokeSession(sessionId, reason)

            call.respond(HttpStatusCode.OK, mapOf("message" to "Session revoked successfully"))
        }

        // DELETE /sessions - Force logout all sessions (except optionally the current one)
        delete {
            val principal = call.kodex
                ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

            val exceptSessionId = call.parameters["exceptSessionId"]?.let { UUID.fromString(it) }

            sessionService.revokeAllSessions(principal.userId, exceptSessionId)

            call.respond(HttpStatusCode.OK, mapOf("message" to "All sessions revoked successfully"))
        }
    }
}

@Serializable
public data class SessionListResponse(
    val sessions: List<SessionDto>
)

@Serializable
public data class SessionDto(
    val id: String,
    val deviceName: String?,
    val ipAddress: String?,
    val location: String?,
    val createdAt: String,
    val lastActivityAt: String,
    val isCurrent: Boolean
)

@Serializable
public data class SessionHistoryResponse(
    val history: List<SessionHistoryDto>
)

@Serializable
public data class SessionHistoryPageResponse(
    val history: List<SessionHistoryDto>,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

@Serializable
public data class SessionHistoryDto(
    val id: String,
    val sessionId: String,
    val deviceName: String?,
    val ipAddress: String?,
    val location: String?,
    val loginAt: String,
    val logoutAt: String?,
    val endReason: String
)
