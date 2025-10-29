package com.mustafadakhel.kodex.event

import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Base interface for all events in Kodex.
 * Events are immutable and carry context about what happened.
 */
public interface KodexEvent {
    /**
     * Unique identifier for this event instance.
     */
    public val eventId: UUID

    /**
     * When this event occurred.
     */
    public val timestamp: Instant

    /**
     * The realm this event belongs to.
     */
    public val realmId: String

    /**
     * Type identifier for this event (e.g., "USER_CREATED").
     */
    public val eventType: String
}
