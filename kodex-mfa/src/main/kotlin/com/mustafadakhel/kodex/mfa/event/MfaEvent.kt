package com.mustafadakhel.kodex.mfa.event

import com.mustafadakhel.kodex.event.EventSeverity
import com.mustafadakhel.kodex.event.FailureReason
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.mfa.MfaMethodType
import kotlinx.datetime.Instant
import java.util.UUID

public sealed interface MfaEvent : KodexEvent {

    public data class EnrollmentStarted(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodType: MfaMethodType,
        val contactValue: String?,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_ENROLLMENT_STARTED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class EnrollmentCompleted(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodId: UUID,
        val methodType: MfaMethodType,
        val isPrimary: Boolean,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_ENROLLMENT_COMPLETED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class EnrollmentFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodType: MfaMethodType,
        val reason: String,
        val failureReason: FailureReason = FailureReason.UNKNOWN,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_ENROLLMENT_FAILED"
    }

    public data class ChallengeSent(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodId: UUID,
        val methodType: MfaMethodType,
        val challengeId: UUID,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_CHALLENGE_SENT"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class VerificationSuccess(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodId: UUID?,
        val methodType: MfaMethodType,
        val challengeId: UUID?,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_VERIFICATION_SUCCESS"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class VerificationFailed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodId: UUID?,
        val methodType: MfaMethodType,
        val challengeId: UUID?,
        val reason: String,
        val failureReason: FailureReason = FailureReason.INVALID_CREDENTIALS,
        val attemptsRemaining: Int?,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val durationMs: Long? = null,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_VERIFICATION_FAILED"
    }

    public data class MethodRemoved(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodId: UUID,
        val methodType: MfaMethodType,
        val actorId: UUID,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_METHOD_REMOVED"
        override val severity: EventSeverity = EventSeverity.WARNING
    }

    public data class PrimaryMethodChanged(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val oldPrimaryMethodId: UUID?,
        val newPrimaryMethodId: UUID,
        val methodType: MfaMethodType,
        val actorId: UUID,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_PRIMARY_METHOD_CHANGED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class BackupCodesGenerated(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val codeCount: Int,
        val actorId: UUID,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_BACKUP_CODES_GENERATED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class BackupCodeUsed(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val codeId: UUID,
        val codesRemaining: Int,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_BACKUP_CODE_USED"
        override val severity: EventSeverity = if (codesRemaining <= 2) EventSeverity.WARNING else EventSeverity.INFO
    }

    public data class RateLimitExceeded(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val methodType: MfaMethodType,
        val operation: String,
        val reason: String,
        val retryAfter: Instant?,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_RATE_LIMIT_EXCEEDED"
    }

    public data class MfaRequired(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val mfaSessionId: String,
        val availableMethods: List<MfaMethodType>,
        val reason: String,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_REQUIRED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class MfaEnrollmentRequired(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val requiredByRoles: List<String>,
        val reason: String,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val severity: EventSeverity = EventSeverity.WARNING,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_ENROLLMENT_REQUIRED"
    }

    public data class DeviceTrusted(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val deviceId: UUID,
        val deviceFingerprint: String,
        val deviceName: String?,
        val expiresAt: Instant?,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_DEVICE_TRUSTED"
        override val severity: EventSeverity = EventSeverity.INFO
    }

    public data class DeviceUntrusted(
        override val eventId: UUID,
        override val timestamp: Instant,
        override val realmId: String,
        val userId: UUID,
        val deviceId: UUID,
        val deviceFingerprint: String,
        val actorId: UUID,
        override val requestId: UUID? = null,
        override val sessionId: UUID? = null,
        override val sourceIp: String? = null,
        override val userAgent: String? = null,
        override val correlationId: UUID? = requestId,
        override val tags: Map<String, String> = emptyMap()
    ) : MfaEvent {
        override val eventType: String = "MFA_DEVICE_UNTRUSTED"
        override val severity: EventSeverity = EventSeverity.WARNING
    }
}
