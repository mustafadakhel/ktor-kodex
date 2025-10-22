package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.event.*
import kotlin.reflect.KClass

/**
 * Event subscriber that converts Kodex events to audit log entries.
 *
 * This subscriber listens to all events and converts them to AuditEvent
 * entries for persistence or forwarding by the configured AuditProvider.
 */
public class AuditEventSubscriber internal constructor(
    private val provider: AuditProvider
) : EventSubscriber<KodexEvent> {

    override val eventType: KClass<out KodexEvent> = KodexEvent::class

    override suspend fun onEvent(event: KodexEvent) {
        val auditEvent = convertToAuditEvent(event)
        try {
            provider.log(auditEvent)
        } catch (e: Exception) {
            System.err.println("Failed to log audit event from ${event.eventType}: ${e.message}")
        }
    }

    private fun convertToAuditEvent(event: KodexEvent): AuditEvent {
        return when (event) {
            is UserEvent.Created -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorType = ActorType.fromString(event.actorType),
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "email" to (event.email ?: ""),
                    "phone" to (event.phone ?: "")
                ),
                realmId = event.realmId
            )

            is UserEvent.Updated -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.actorId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = event.changes,
                realmId = event.realmId
            )

            is UserEvent.ProfileUpdated -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.actorId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = event.changes,
                realmId = event.realmId
            )

            is UserEvent.RolesUpdated -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorType = ActorType.fromString(event.actorType),
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "previousRoles" to event.previousRoles.joinToString(","),
                    "newRoles" to event.newRoles.joinToString(","),
                    "addedRoles" to (event.newRoles - event.previousRoles).joinToString(","),
                    "removedRoles" to (event.previousRoles - event.newRoles).joinToString(",")
                ),
                realmId = event.realmId
            )

            is UserEvent.CustomAttributesUpdated -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.actorId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "attributeCount" to event.attributeCount.toString(),
                    "keys" to event.keys.joinToString(",")
                ),
                realmId = event.realmId
            )

            is UserEvent.CustomAttributesReplaced -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.actorId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "attributeCount" to event.attributeCount.toString(),
                    "keys" to event.keys.joinToString(",")
                ),
                realmId = event.realmId
            )

            is AuthEvent.LoginSuccess -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "identifier" to event.identifier,
                    "method" to event.method
                ),
                realmId = event.realmId
            )

            is AuthEvent.LoginFailed -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorType = ActorType.fromString(event.actorType),
                targetId = event.userId,
                result = EventResult.FAILURE,
                metadata = mapOf(
                    "identifier" to event.identifier,
                    "reason" to event.reason,
                    "method" to event.method
                ),
                realmId = event.realmId
            )

            is AuthEvent.PasswordChanged -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.actorId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = event.realmId
            )

            is AuthEvent.PasswordChangeFailed -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.actorId,
                actorType = ActorType.USER,
                targetId = event.userId,
                result = EventResult.FAILURE,
                metadata = mapOf("reason" to event.reason),
                realmId = event.realmId
            )

            is AuthEvent.PasswordReset -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorType = ActorType.fromString(event.actorType),
                targetId = event.userId,
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = event.realmId
            )

            is SecurityEvent.TokenReplayDetected -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                actorType = ActorType.USER,
                targetId = event.tokenId,
                targetType = "refresh_token",
                result = EventResult.FAILURE,
                metadata = mapOf(
                    "reason" to "Refresh token replay attack detected",
                    "tokenId" to event.tokenId.toString(),
                    "tokenFamily" to event.tokenFamily.toString(),
                    "firstUsedAt" to event.firstUsedAt,
                    "gracePeriodEnd" to event.gracePeriodEnd
                ),
                realmId = event.realmId
            )

            else -> throw IllegalArgumentException("Unsupported event type: ${event::class.simpleName}")
        }
    }
}
