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

    /**
     * User was created.
     */
    public data class Created(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val email: String?,
        val phone: String?,
        val actorType: String = "SYSTEM"
    ) : UserEvent {
        override val eventType: String = "USER_CREATED"
    }

    /**
     * User profile or credentials were updated.
     */
    public data class Updated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val changes: Map<String, String>
    ) : UserEvent {
        override val eventType: String = "USER_UPDATED"
    }

    /**
     * User profile was updated.
     */
    public data class ProfileUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val changes: Map<String, String>
    ) : UserEvent {
        override val eventType: String = "USER_PROFILE_UPDATED"
    }

    /**
     * User roles were updated.
     */
    public data class RolesUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val previousRoles: Set<String>,
        val newRoles: Set<String>,
        val actorType: String = "ADMIN"
    ) : UserEvent {
        override val eventType: String = "USER_ROLES_UPDATED"
    }

    /**
     * User custom attributes were updated.
     */
    public data class CustomAttributesUpdated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val attributeCount: Int,
        val keys: Set<String>
    ) : UserEvent {
        override val eventType: String = "CUSTOM_ATTRIBUTES_UPDATED"
    }

    /**
     * User custom attributes were replaced.
     */
    public data class CustomAttributesReplaced(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        override val userId: UUID,
        val actorId: UUID,
        val attributeCount: Int,
        val keys: Set<String>
    ) : UserEvent {
        override val eventType: String = "CUSTOM_ATTRIBUTES_REPLACED"
    }
}
