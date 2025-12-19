package com.mustafadakhel.kodex.audit

import com.mustafadakhel.kodex.event.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Audit event subscriber tests.
 * Tests event mapping from Kodex events to AuditEvent entries.
 */
class AuditEventSubscriberTest : StringSpec({

    // Collect events in memory for testing
    class InMemoryAuditProvider : AuditProvider {
        val events = mutableListOf<AuditEvent>()
        override suspend fun log(event: AuditEvent) {
            events.add(event)
        }
    }

    "Login success event should create audit entry with correct fields" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = AuthEvent.LoginSuccess(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            identifier = "user@example.com",
            method = "password"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "LOGIN_SUCCESS"
        auditEvent.actorId shouldBe userId
        auditEvent.actorType shouldBe ActorType.USER
        auditEvent.targetId shouldBe userId
        auditEvent.result shouldBe EventResult.SUCCESS
        auditEvent.realmId shouldBe "test-realm"
        auditEvent.metadata["identifier"] shouldBe "user@example.com"
        auditEvent.metadata["method"] shouldBe "password"
    }

    "Login failure event should create audit entry with failure result" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = AuthEvent.LoginFailed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            identifier = "user@example.com",
            reason = "invalid_credentials",
            method = "password",
            userId = userId,
            actorType = "anonymous"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "LOGIN_FAILED"
        auditEvent.targetId shouldBe userId
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["identifier"] shouldBe "user@example.com"
        auditEvent.metadata["reason"] shouldBe "invalid_credentials"
        auditEvent.metadata["method"] shouldBe "password"
    }

    "Login failure for non-existent user should have null userId" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val event = AuthEvent.LoginFailed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            identifier = "nonexistent@example.com",
            reason = "user_not_found",
            method = "password",
            userId = null,
            actorType = "anonymous"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.targetId shouldBe null
        auditEvent.actorType shouldBe ActorType.ANONYMOUS
    }

    "Token issued event should create audit entry" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val tokenId = UUID.randomUUID()
        val tokenFamily = UUID.randomUUID()
        val event = TokenEvent.Issued(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            tokenId = tokenId,
            tokenFamily = tokenFamily
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "TOKEN_ISSUED"
        auditEvent.actorId shouldBe userId
        auditEvent.targetId shouldBe tokenId
        auditEvent.targetType shouldBe "access_token"
        auditEvent.result shouldBe EventResult.SUCCESS
    }

    "Token refreshed event should include old and new token IDs" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val oldTokenId = UUID.randomUUID()
        val newTokenId = UUID.randomUUID()
        val tokenFamily = UUID.randomUUID()
        val event = TokenEvent.Refreshed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            oldTokenId = oldTokenId,
            newTokenId = newTokenId,
            tokenFamily = tokenFamily
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "TOKEN_REFRESHED"
        auditEvent.metadata["oldTokenId"] shouldBe oldTokenId.toString()
        auditEvent.metadata["newTokenId"] shouldBe newTokenId.toString()
    }

    "Token revoked event should include revocation details" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val tokenId1 = UUID.randomUUID()
        val tokenId2 = UUID.randomUUID()
        val event = TokenEvent.Revoked(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            tokenIds = listOf(tokenId1, tokenId2),
            revokedCount = 2
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "TOKEN_REVOKED"
        auditEvent.metadata["revokedCount"] shouldBe "2"
        val tokenIds = auditEvent.metadata["tokenIds"] as String
        tokenIds shouldContain tokenId1.toString()
        tokenIds shouldContain tokenId2.toString()
    }

    "Token refresh failed event should include failure reason" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = TokenEvent.RefreshFailed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            reason = "token_expired"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "TOKEN_REFRESH_FAILED"
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["reason"] shouldBe "token_expired"
    }

    "Token verify failed event should be anonymous actor" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val event = TokenEvent.VerifyFailed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            reason = "invalid_signature"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "TOKEN_VERIFY_FAILED"
        auditEvent.actorType shouldBe ActorType.ANONYMOUS
        auditEvent.result shouldBe EventResult.FAILURE
    }

    "Token replay detected event should be security-critical" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val tokenId = UUID.randomUUID()
        val tokenFamily = UUID.randomUUID()
        val event = SecurityEvent.TokenReplayDetected(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            tokenId = tokenId,
            tokenFamily = tokenFamily,
            firstUsedAt = "2024-01-01T00:00:00Z",
            gracePeriodEnd = "2024-01-01T00:00:05Z",
            sourceIp = "192.168.1.1",
            userAgent = "Chrome/120"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "SECURITY_VIOLATION"
        auditEvent.actorId shouldBe userId
        auditEvent.targetId shouldBe tokenId
        auditEvent.targetType shouldBe "refresh_token"
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["tokenFamily"] shouldBe tokenFamily.toString()
        auditEvent.metadata["sourceIp"] shouldBe "192.168.1.1"
    }

    "User created event should include email and phone" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = UserEvent.Created(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            email = "user@example.com",
            phone = "+1234567890",
            actorType = "system"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "USER_CREATED"
        auditEvent.targetId shouldBe userId
        auditEvent.result shouldBe EventResult.SUCCESS
        auditEvent.metadata["email"] shouldBe "user@example.com"
        auditEvent.metadata["phone"] shouldBe "+1234567890"
    }

    "User updated event should include changes" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val event = UserEvent.Updated(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            actorId = actorId,
            changes = mapOf("email" to "new@example.com")
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "USER_UPDATED"
        auditEvent.actorId shouldBe actorId
        auditEvent.targetId shouldBe userId
        auditEvent.metadata["email"] shouldBe "new@example.com"
    }

    "Roles updated event should show role changes" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = UserEvent.RolesUpdated(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            previousRoles = setOf("user"),
            newRoles = setOf("user", "admin"),
            actorType = "admin"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "USER_ROLES_UPDATED"
        auditEvent.targetId shouldBe userId
        auditEvent.metadata["previousRoles"] shouldBe "user"
        auditEvent.metadata["addedRoles"] shouldBe "admin"
    }

    "Password changed event should NOT include password value" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = AuthEvent.PasswordChanged(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            actorId = userId
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "PASSWORD_CHANGED"
        auditEvent.metadata.toString() shouldNotContain "password"
        auditEvent.metadata.toString() shouldNotContain "secret"
    }

    "Password change failed event should include reason but no password" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = AuthEvent.PasswordChangeFailed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            actorId = userId,
            reason = "current_password_incorrect"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "PASSWORD_CHANGE_FAILED"
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["reason"] shouldBe "current_password_incorrect"
    }

    "Session created event should include device info" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val tokenFamily = UUID.randomUUID()
        val event = SessionEvent.SessionCreated(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            kodexSessionId = sessionId,
            userId = userId,
            tokenFamily = tokenFamily,
            deviceFingerprint = "abc123fingerprint",
            deviceName = "Chrome on Windows",
            ipAddress = "192.168.1.1",
            location = "New York, USA"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "SESSION_CREATED"
        auditEvent.actorId shouldBe userId
        auditEvent.targetId shouldBe sessionId
        auditEvent.targetType shouldBe "session"
        auditEvent.metadata["tokenFamily"] shouldBe tokenFamily.toString()
        auditEvent.metadata["deviceFingerprint"] shouldBe "abc123fingerprint"
        auditEvent.metadata["deviceName"] shouldBe "Chrome on Windows"
        auditEvent.metadata["ipAddress"] shouldBe "192.168.1.1"
        auditEvent.metadata["location"] shouldBe "New York, USA"
    }

    "Session revoked event should include reason and revoker" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val event = SessionEvent.SessionRevoked(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            kodexSessionId = sessionId,
            userId = userId,
            reason = "USER_INITIATED",
            revokedBy = "user"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "SESSION_REVOKED"
        auditEvent.actorType shouldBe ActorType.USER
        auditEvent.metadata["reason"] shouldBe "USER_INITIATED"
        auditEvent.metadata["revokedBy"] shouldBe "user"
    }

    "Session activity updated event should include timestamp" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val lastActivity = Clock.System.now()
        val event = SessionEvent.SessionActivityUpdated(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            kodexSessionId = sessionId,
            userId = userId,
            lastActivityAt = lastActivity
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "SESSION_ACTIVITY_UPDATED"
        auditEvent.metadata["lastActivityAt"] shouldBe lastActivity.toString()
    }

    "Session anomaly detected event should be security-critical" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val event = SessionEvent.SessionAnomalyDetected(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            kodexSessionId = sessionId,
            userId = userId,
            anomalyType = "new_device",
            details = mapOf(
                "previousFingerprint" to "old123",
                "newFingerprint" to "new456"
            )
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "SESSION_ANOMALY_DETECTED"
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["anomalyType"] shouldBe "new_device"
        auditEvent.metadata["previousFingerprint"] shouldBe "old123"
        auditEvent.metadata["newFingerprint"] shouldBe "new456"
    }

    "Session expired event should be system-initiated" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val expiredAt = Clock.System.now()
        val event = SessionEvent.SessionExpired(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            kodexSessionId = sessionId,
            userId = userId,
            expiredAt = expiredAt
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "SESSION_EXPIRED"
        auditEvent.actorType shouldBe ActorType.SYSTEM
        auditEvent.metadata["expiredAt"] shouldBe expiredAt.toString()
    }

    "Rate limit exceeded event should be anonymous" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val event = SecurityEvent.RateLimitExceeded(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            identifier = "attacker@example.com",
            limitType = "login_attempts",
            threshold = 5,
            currentCount = 6,
            sourceIp = "192.168.1.100"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "RATE_LIMIT_EXCEEDED"
        auditEvent.actorType shouldBe ActorType.ANONYMOUS
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["identifier"] shouldBe "attacker@example.com"
        auditEvent.metadata["limitType"] shouldBe "login_attempts"
        auditEvent.metadata["threshold"] shouldBe "5"
        auditEvent.metadata["currentCount"] shouldBe "6"
    }

    "Account locked event should be system-initiated" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = SecurityEvent.AccountLocked(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            reason = "too_many_failed_attempts",
            lockDurationMs = 1800000L // 30 minutes
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "ACCOUNT_LOCKED"
        auditEvent.actorType shouldBe ActorType.SYSTEM
        auditEvent.targetId shouldBe userId
        auditEvent.result shouldBe EventResult.SUCCESS
        auditEvent.metadata["reason"] shouldBe "too_many_failed_attempts"
        auditEvent.metadata["lockDurationMs"] shouldBe "1800000"
    }

    "Account unlocked event should show who unlocked" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = SecurityEvent.AccountUnlocked(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            unlockedBy = "admin"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "ACCOUNT_UNLOCKED"
        auditEvent.actorType shouldBe ActorType.ADMIN
        auditEvent.targetId shouldBe userId
    }

    "Email verification sent event" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = VerificationEvent.EmailVerificationSent(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            email = "user@example.com"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "EMAIL_VERIFICATION_SENT"
        auditEvent.actorType shouldBe ActorType.SYSTEM
        auditEvent.metadata["email"] shouldBe "user@example.com"
    }

    "Email verified event" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = VerificationEvent.EmailVerified(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            email = "user@example.com"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "EMAIL_VERIFIED"
        auditEvent.actorId shouldBe userId
        auditEvent.actorType shouldBe ActorType.USER
    }

    "Verification failed event" {
        val provider = InMemoryAuditProvider()
        val subscriber = AuditEventSubscriber(provider)

        val userId = UUID.randomUUID()
        val event = VerificationEvent.VerificationFailed(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = userId,
            verificationType = "email",
            reason = "token_expired"
        )

        runBlocking { subscriber.onEvent(event) }

        provider.events shouldHaveSize 1
        val auditEvent = provider.events[0]

        auditEvent.eventType shouldBe "VERIFICATION_FAILED"
        auditEvent.result shouldBe EventResult.FAILURE
        auditEvent.metadata["verificationType"] shouldBe "email"
        auditEvent.metadata["reason"] shouldBe "token_expired"
    }

    "Should not throw when provider fails" {
        val failingProvider = object : AuditProvider {
            override suspend fun log(event: AuditEvent) {
                throw RuntimeException("Database connection failed")
            }
        }
        val subscriber = AuditEventSubscriber(failingProvider)

        val event = AuthEvent.LoginSuccess(
            eventId = UUID.randomUUID(),
            timestamp = Clock.System.now(),
            realmId = "test-realm",
            userId = UUID.randomUUID(),
            identifier = "user@example.com",
            method = "password"
        )

        // Should not throw
        runBlocking { subscriber.onEvent(event) }
    }
})
