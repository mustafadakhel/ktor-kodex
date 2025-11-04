package com.mustafadakhel.kodex.metrics

import com.mustafadakhel.kodex.event.*
import kotlin.reflect.KClass

public class MetricsEventSubscriber internal constructor(
    private val metrics: KodexMetrics
) : EventSubscriber<KodexEvent> {

    override val eventType: KClass<out KodexEvent> = KodexEvent::class

    override suspend fun onEvent(event: KodexEvent) {
        try {
            when (event) {
                is AuthEvent.LoginSuccess -> {
                    metrics.recordAuthentication(
                        success = true,
                        reason = event.method,
                        realmId = event.realmId
                    )
                }

                is AuthEvent.LoginFailed -> {
                    metrics.recordAuthentication(
                        success = false,
                        reason = event.reason,
                        realmId = event.realmId
                    )
                }

                is AuthEvent.PasswordChanged -> {}
                is AuthEvent.PasswordChangeFailed -> {}
                is AuthEvent.PasswordReset -> {}

                is TokenEvent.Issued -> {
                    metrics.recordTokenOperation(
                        operation = "issue",
                        tokenType = "access+refresh",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is TokenEvent.Refreshed -> {
                    metrics.recordTokenOperation(
                        operation = "refresh",
                        tokenType = "access+refresh",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is TokenEvent.RefreshFailed -> {
                    metrics.recordTokenOperation(
                        operation = "refresh",
                        tokenType = "refresh",
                        success = false,
                        realmId = event.realmId
                    )
                }

                is UserEvent.Created -> {
                    metrics.recordUserOperation(
                        operation = "create",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is UserEvent.Updated -> {
                    metrics.recordUserOperation(
                        operation = "update",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is UserEvent.ProfileUpdated -> {
                    metrics.recordUserOperation(
                        operation = "profile_update",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is UserEvent.RolesUpdated -> {
                    metrics.recordUserOperation(
                        operation = "roles_update",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is UserEvent.CustomAttributesUpdated -> {
                    metrics.recordUserOperation(
                        operation = "attributes_update",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is UserEvent.CustomAttributesReplaced -> {
                    metrics.recordUserOperation(
                        operation = "attributes_replace",
                        success = true,
                        realmId = event.realmId
                    )
                }

                is SecurityEvent.TokenReplayDetected -> {
                    metrics.recordTokenOperation(
                        operation = "replay_detected",
                        tokenType = "refresh",
                        success = false,
                        realmId = event.realmId
                    )
                }

                is SecurityEvent.RateLimitExceeded -> {
                    metrics.recordAccountLockout(locked = true, realmId = event.realmId)
                }

                is SecurityEvent.AccountLocked -> {
                    metrics.recordAccountLockout(locked = true, realmId = event.realmId)
                }

                is SecurityEvent.AccountUnlocked -> {
                    metrics.recordAccountLockout(locked = false, realmId = event.realmId)
                }

                is VerificationEvent.EmailVerificationSent -> {
                    metrics.recordVerificationSend(
                        success = true,
                        contactType = "email",
                        reason = null
                    )
                }

                is VerificationEvent.EmailVerified -> {
                    metrics.recordVerificationAttempt(
                        success = true,
                        reason = null
                    )
                }

                is VerificationEvent.PhoneVerificationSent -> {
                    metrics.recordVerificationSend(
                        success = true,
                        contactType = "phone",
                        reason = null
                    )
                }

                is VerificationEvent.PhoneVerified -> {
                    metrics.recordVerificationAttempt(
                        success = true,
                        reason = null
                    )
                }

                is VerificationEvent.VerificationFailed -> {
                    metrics.recordVerificationAttempt(
                        success = false,
                        reason = event.reason
                    )
                }

                is PasswordResetEvent.PasswordResetInitiated -> {
                    metrics.recordPasswordResetInitiate(
                        success = true,
                        contactType = event.contactType,
                        reason = null
                    )
                }

                is PasswordResetEvent.PasswordResetInitiationFailed -> {
                    metrics.recordPasswordResetInitiate(
                        success = false,
                        contactType = event.contactType,
                        reason = event.reason
                    )
                }

                is PasswordResetEvent.PasswordResetTokenVerified -> {
                    metrics.recordPasswordResetVerify(
                        success = true,
                        reason = null
                    )
                }

                is PasswordResetEvent.PasswordResetTokenVerificationFailed -> {
                    metrics.recordPasswordResetVerify(
                        success = false,
                        reason = event.reason
                    )
                }

                is PasswordResetEvent.PasswordResetCompleted -> {
                    metrics.recordPasswordResetConsume(
                        success = true,
                        reason = null
                    )
                }

                is PasswordResetEvent.PasswordResetCompletionFailed -> {
                    metrics.recordPasswordResetConsume(
                        success = false,
                        reason = event.reason
                    )
                }

                is RateLimitEvent.RateLimitChecked -> {
                    metrics.recordRateLimitCheck(
                        allowed = event.allowed,
                        key = event.key
                    )
                }

                is RateLimitEvent.RateLimitSizeChanged -> {
                    metrics.recordRateLimitSize(event.size)
                }

                is RateLimitEvent.RateLimitCleanupPerformed -> {
                    metrics.recordRateLimitCleanup(event.entriesRemoved)
                }

                is RateLimitEvent.RateLimitEvictionPerformed -> {
                    metrics.recordRateLimitEviction(event.entriesEvicted)
                }

                is TokenCleanupEvent.TokensCleanedUp -> {
                    metrics.recordTokenCleanup(
                        tokenType = event.tokenType,
                        tokensRemoved = event.tokensRemoved
                    )
                }

                else -> {}
            }
        } catch (e: Exception) {
            System.err.println("Failed to record metrics for event ${event.eventType}: ${e.message}")
        }
    }
}
