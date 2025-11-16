package com.mustafadakhel.kodex.sample.routing

import com.mustafadakhel.kodex.extensionService
import com.mustafadakhel.kodex.routes.auth.KodexId
import com.mustafadakhel.kodex.routes.auth.authenticateFor
import com.mustafadakhel.kodex.routes.auth.kodex
import com.mustafadakhel.kodex.sample.DefaultRealms
import com.mustafadakhel.kodex.sessions.SessionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

fun Application.setupSessionRouting() = routing {
    DefaultRealms.forEach { realm ->
        authenticateFor(realm) {
            route("/${realm.owner}/sessions") {
                // GET /sessions - List active sessions for the authenticated user
                get {
                val userId = with(KodexId) { call.idOrFail() }
                val sessionService = call.extensionService<SessionService>(realm)
                    ?: return@get call.respondText(
                        "Session service not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                try {
                    val sessions = sessionService.listActiveSessions(userId)

                    // Get current tokenFamily from principal to mark current session
                    val currentTokenFamily = call.kodex?.tokenFamily

                    call.respond(
                        SessionListResponse(
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
                        )
                    )
                } catch (e: Exception) {
                    call.respondText(
                        "Failed to list sessions: ${e.message}",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }

            // GET /sessions/history - Get paginated session history
            get("/history") {
                val userId = with(KodexId) { call.idOrFail() }
                val sessionService = call.extensionService<SessionService>(realm)
                    ?: return@get call.respondText(
                        "Session service not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                val limit = call.parameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

                try {
                    val historyPage = sessionService.getSessionHistoryPage(userId, limit, offset)

                    call.respond(
                        SessionHistoryPageResponse(
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
                        )
                    )
                } catch (e: Exception) {
                    call.respondText(
                        "Failed to get session history: ${e.message}",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }

            // DELETE /sessions/:sessionId - Revoke a specific session
            delete("/{sessionId}") {
                val userId = with(KodexId) { call.idOrFail() }
                val sessionService = call.extensionService<SessionService>(realm)
                    ?: return@delete call.respondText(
                        "Session service not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                val sessionIdStr = call.parameters["sessionId"]
                    ?: return@delete call.respondText(
                        "Missing sessionId",
                        status = HttpStatusCode.BadRequest
                    )

                try {
                    val sessionId = UUID.fromString(sessionIdStr)

                    // Verify the session belongs to the authenticated user
                    val session = sessionService.getSession(sessionId)
                    if (session == null) {
                        return@delete call.respondText(
                            "Session not found",
                            status = HttpStatusCode.NotFound
                        )
                    }
                    if (session.userId != userId) {
                        return@delete call.respondText(
                            "Access denied",
                            status = HttpStatusCode.Forbidden
                        )
                    }

                    val reason = call.parameters["reason"] ?: "user_revoked"
                    sessionService.revokeSession(sessionId, reason)

                    call.respond(
                        HttpStatusCode.OK,
                        SessionActionResponse(success = true, message = "Session revoked successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respondText("Invalid sessionId format", status = HttpStatusCode.BadRequest)
                } catch (e: Exception) {
                    call.respondText(
                        "Failed to revoke session: ${e.message}",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }

            // DELETE /sessions - Force logout all sessions (except optionally the current one)
            delete {
                val userId = with(KodexId) { call.idOrFail() }
                val sessionService = call.extensionService<SessionService>(realm)
                    ?: return@delete call.respondText(
                        "Session service not configured",
                        status = HttpStatusCode.InternalServerError
                    )

                val exceptSessionIdStr = call.parameters["exceptSessionId"]
                val exceptSessionId = exceptSessionIdStr?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: IllegalArgumentException) {
                        return@delete call.respondText(
                            "Invalid exceptSessionId format",
                            status = HttpStatusCode.BadRequest
                        )
                    }
                }

                try {
                    sessionService.revokeAllSessions(userId, exceptSessionId)

                    call.respond(
                        HttpStatusCode.OK,
                        SessionActionResponse(success = true, message = "All sessions revoked successfully")
                    )
                } catch (e: Exception) {
                    call.respondText(
                        "Failed to revoke sessions: ${e.message}",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
            }
        }
    }
}

@Serializable
data class SessionListResponse(
    val sessions: List<SessionDto>
)

@Serializable
data class SessionDto(
    val id: String,
    val deviceName: String?,
    val ipAddress: String?,
    val location: String?,
    val createdAt: String,
    val lastActivityAt: String,
    val isCurrent: Boolean
)

@Serializable
data class SessionHistoryPageResponse(
    val history: List<SessionHistoryDto>,
    val totalCount: Long,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

@Serializable
data class SessionHistoryDto(
    val id: String,
    val sessionId: String,
    val deviceName: String?,
    val ipAddress: String?,
    val location: String?,
    val loginAt: String,
    val logoutAt: String?,
    val endReason: String
)

@Serializable
data class SessionActionResponse(
    val success: Boolean,
    val message: String
)
