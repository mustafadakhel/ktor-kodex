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
                metadata = buildMap {
                    put("reason", "Refresh token replay attack detected")
                    put("tokenId", event.tokenId.toString())
                    put("tokenFamily", event.tokenFamily.toString())
                    put("firstUsedAt", event.firstUsedAt)
                    put("gracePeriodEnd", event.gracePeriodEnd)
                    event.sourceIp?.let { put("sourceIp", it) }
                    event.userAgent?.let { put("userAgent", it) }
                },
                realmId = event.realmId
            )

            is SecurityEvent.RateLimitExceeded -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorType = ActorType.ANONYMOUS,
                result = EventResult.FAILURE,
                metadata = buildMap {
                    put("identifier", event.identifier)
                    put("limitType", event.limitType)
                    put("threshold", event.threshold.toString())
                    put("currentCount", event.currentCount.toString())
                    event.sourceIp?.let { put("sourceIp", it) }
                },
                realmId = event.realmId
            )

            is SecurityEvent.AccountLocked -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                targetId = event.userId,
                actorType = ActorType.SYSTEM,
                result = EventResult.SUCCESS,
                metadata = buildMap {
                    put("reason", event.reason)
                    event.lockDurationMs?.let { put("lockDurationMs", it.toString()) }
                },
                realmId = event.realmId
            )

            is SecurityEvent.AccountUnlocked -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                targetId = event.userId,
                actorType = ActorType.fromString(event.unlockedBy),
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = event.realmId
            )

            is TokenEvent.Issued -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                actorType = ActorType.USER,
                targetId = event.tokenId,
                targetType = "access_token",
                result = EventResult.SUCCESS,
                metadata = emptyMap(),
                realmId = event.realmId
            )

            is TokenEvent.Refreshed -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                actorType = ActorType.USER,
                targetId = event.newTokenId,
                targetType = "access_token",
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "oldTokenId" to event.oldTokenId.toString(),
                    "newTokenId" to event.newTokenId.toString()
                ),
                realmId = event.realmId
            )

            is TokenEvent.RefreshFailed -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                actorType = ActorType.USER,
                result = EventResult.FAILURE,
                metadata = mapOf("reason" to event.reason),
                realmId = event.realmId
            )

            is TokenEvent.Revoked -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                actorType = ActorType.USER,
                result = EventResult.SUCCESS,
                metadata = mapOf(
                    "revokedCount" to event.revokedCount.toString(),
                    "tokenIds" to event.tokenIds.joinToString(",")
                ),
                realmId = event.realmId
            )

            is TokenEvent.VerifyFailed -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorType = ActorType.ANONYMOUS,
                result = EventResult.FAILURE,
                metadata = mapOf("reason" to event.reason),
                realmId = event.realmId
            )

            is VerificationEvent.EmailVerificationSent -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                targetId = event.userId,
                actorType = ActorType.SYSTEM,
                result = EventResult.SUCCESS,
                metadata = mapOf("email" to event.email),
                realmId = event.realmId
            )

            is VerificationEvent.EmailVerified -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                targetId = event.userId,
                actorType = ActorType.USER,
                result = EventResult.SUCCESS,
                metadata = mapOf("email" to event.email),
                realmId = event.realmId
            )

            is VerificationEvent.PhoneVerificationSent -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                targetId = event.userId,
                actorType = ActorType.SYSTEM,
                result = EventResult.SUCCESS,
                metadata = mapOf("phone" to event.phone),
                realmId = event.realmId
            )

            is VerificationEvent.PhoneVerified -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                actorId = event.userId,
                targetId = event.userId,
                actorType = ActorType.USER,
                result = EventResult.SUCCESS,
                metadata = mapOf("phone" to event.phone),
                realmId = event.realmId
            )

            is VerificationEvent.VerificationFailed -> AuditEvent(
                eventType = event.eventType,
                timestamp = event.timestamp,
                targetId = event.userId,
                actorType = ActorType.USER,
                result = EventResult.FAILURE,
                metadata = mapOf(
                    "verificationType" to event.verificationType,
                    "reason" to event.reason
                ),
                realmId = event.realmId
            )

            else -> throw IllegalArgumentException("Unsupported event type: ${event::class.simpleName}")
        }
    }
}
