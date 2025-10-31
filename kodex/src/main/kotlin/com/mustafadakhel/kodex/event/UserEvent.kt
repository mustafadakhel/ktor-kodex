package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Events related to user lifecycle and management.
 */
public sealed interface UserEvent : KodexEvent {
    /**
     * The user this event is about.
     */
    public val userId: UUID

    public data class Created(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val email: String?,
        val phone: String?,
        val actorType: String = "SYSTEM",
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "USER_CREATED"
    }

    public data class Updated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val changes: Map<String, String>,
        val fieldChanges: List<FieldChange> = emptyList(),
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "USER_UPDATED"
    }

    public data class ProfileUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val changes: Map<String, String>,
        val fieldChanges: List<FieldChange> = emptyList(),
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "USER_PROFILE_UPDATED"
    }

    public data class RolesUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val previousRoles: Set<String>,
        val newRoles: Set<String>,
        val actorType: String = "ADMIN",
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "USER_ROLES_UPDATED"
    }

    public data class CustomAttributesUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val attributeCount: Int,
        val keys: Set<String>,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "CUSTOM_ATTRIBUTES_UPDATED"
    }

    public data class CustomAttributesReplaced(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val attributeCount: Int,
        val keys: Set<String>,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "CUSTOM_ATTRIBUTES_REPLACED"
    }

    public data class Deleted(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        override val requestId: UUID? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : UserEvent {
        override val eventType: String = "USER_DELETED"
    }
}
