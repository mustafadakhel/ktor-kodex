package com.mustafadakhel.kodex.audit

import kotlinx.datetime.Instant
import java.util.*

/**
 * Audit event with type-safe core fields and flexible metadata.
 * This is a core data class used by extensions for audit logging.
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

/**
 * Type of actor performing the action.
 */
public enum class ActorType {
    USER,
    ADMIN,
    SYSTEM,
    ANONYMOUS;

    public companion object {
        public fun fromString(value: String): ActorType = when (value.uppercase()) {
            "USER" -> USER
            "ADMIN" -> ADMIN
            "SYSTEM" -> SYSTEM
            "ANONYMOUS" -> ANONYMOUS
            else -> USER
        }
    }
}

/**
 * Result of the audited operation.
 */
public enum class EventResult {
    SUCCESS,
    FAILURE,
    PARTIAL_SUCCESS;

    public companion object {
        public fun fromString(value: String): EventResult = when (value.uppercase()) {
            "SUCCESS" -> SUCCESS
            "FAILURE" -> FAILURE
            "PARTIAL_SUCCESS", "PARTIAL" -> PARTIAL_SUCCESS
            else -> SUCCESS
        }
    }
}
