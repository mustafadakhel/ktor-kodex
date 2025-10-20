package com.mustafadakhel.kodex.audit

import kotlinx.datetime.Instant
import java.util.*

/**
 * Audit event with type-safe core fields and flexible metadata.
 */
public data class AuditEvent(
    val eventType: String,
    val timestamp: Instant,
    val actorId: UUID? = null,
    val actorType: ActorType = ActorType.USER,
    val targetId: UUID? = null,
    val targetType: String? = null,
    val result: EventResult = EventResult.SUCCESS,
    val metadata: Map<String, Any> = emptyMap(),
    val realmId: String,
    val sessionId: UUID? = null
)

public enum class ActorType {
    USER,
    ADMIN,
    SYSTEM,
    ANONYMOUS
}

public enum class EventResult {
    SUCCESS,
    FAILURE,
    PARTIAL_SUCCESS
}
