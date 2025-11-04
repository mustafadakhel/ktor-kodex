package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetContacts
import com.mustafadakhel.kodex.passwordreset.database.PasswordResetTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for PasswordResetService.
 *
 * These tests prove the end-to-end password reset flow works correctly with:
 * - Database persistence
 * - Token generation and validation
 * - Rate limiting with two-phase rollback
 * - User enumeration prevention
 * - Sender integration
 */
class PasswordResetServiceIntegrationTest : FunSpec({

    // Test database setup
    val database = Database.connect("jdbc:h2:mem:test_password_reset_integration;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    val timeZone = TimeZone.UTC

    val mockEventBus = object : EventBus {
        override suspend fun publish(event: KodexEvent) {}
        override fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>) {}
        override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {}
        override fun shutdown() {}
    }

    val testContext = object : ExtensionContext {
        override val realm = Realm(owner = "test")
        override val timeZone = timeZone
        override val eventBus = mockEventBus
    }

    // Mock sender that tracks calls
    class MockPasswordResetSender : PasswordResetSender {
        val sentTokens = mutableListOf<Triple<String, String, String>>() // (recipient, token, expiresAt)
        var shouldFail = false

        override suspend fun send(recipient: String, token: String, expiresAt: String) {
            if (shouldFail) {
                throw RuntimeException("Sender failure simulation")
            }
            sentTokens.add(Triple(recipient, token, expiresAt))
        }

        fun reset() {
            sentTokens.clear()
            shouldFail = false
        }
    }

    val mockSender = MockPasswordResetSender()

    beforeTest {
        // Create tables
        transaction(database) {
            SchemaUtils.create(PasswordResetContacts, PasswordResetTokens)
        }
        mockSender.reset()
    }

    afterTest {
        // Drop tables
        transaction(database) {
            SchemaUtils.drop(PasswordResetContacts, PasswordResetTokens)
        }
    }

    context("End-to-End Password Reset Flow") {
        test("complete flow: initiate → verify → consume → success") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 5
                maxAttemptsPerIdentifier = 5
                maxAttemptsPerIp = 10
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            // Setup: Register user contact
            val userId = UUID.randomUUID()
            val email = "user@example.com"
            transaction(database) {
                PasswordResetContacts.insert {
                    it[PasswordResetContacts.userId] = userId
                    it[contactType] = "EMAIL"
                    it[contactValue] = email
                    it[createdAt] = Clock.System.now().toLocalDateTime(timeZone)
                    it[updatedAt] = Clock.System.now().toLocalDateTime(timeZone)
                }
            }

            // Step 1: Initiate password reset
            val initiateResult = service.initiatePasswordReset(
                identifier = email,
                contactType = PasswordResetService.ContactType.EMAIL,
                ipAddress = "192.168.1.1"
            )
            initiateResult.shouldBeInstanceOf<PasswordResetResult.Success>()

            // Verify token was sent
            mockSender.sentTokens.size shouldBe 1
            val (recipient, token, _) = mockSender.sentTokens.first()
            recipient shouldBe email
            token.shouldNotBeNull()

            // Verify token is stored in database
            val tokenCount = transaction(database) {
                PasswordResetTokens.selectAll().count()
            }
            tokenCount shouldBe 1

            // Step 2: Verify token is valid
            val verifyResult = service.verifyResetToken(token)
            verifyResult.shouldBeInstanceOf<TokenVerificationResult.Valid>()
            (verifyResult as TokenVerificationResult.Valid).userId shouldBe userId

            // Step 3: Consume token (mark as used)
            val consumeResult = service.consumeResetToken(token)
            consumeResult.shouldBeInstanceOf<TokenConsumptionResult.Success>()
            (consumeResult as TokenConsumptionResult.Success).userId shouldBe userId

            // Step 4: Verify token cannot be reused
            val verifyAgain = service.verifyResetToken(token)
            verifyAgain.shouldBeInstanceOf<TokenVerificationResult.Invalid>()

            val consumeAgain = service.consumeResetToken(token)
            consumeAgain.shouldBeInstanceOf<TokenConsumptionResult.Invalid>()
        }

        test("invalid token should be rejected") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val verifyResult = service.verifyResetToken("invalid-token-12345")
            verifyResult.shouldBeInstanceOf<TokenVerificationResult.Invalid>()

            val consumeResult = service.consumeResetToken("invalid-token-12345")
            consumeResult.shouldBeInstanceOf<TokenConsumptionResult.Invalid>()
        }

        test("expired token should be rejected") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val userId = UUID.randomUUID()

            // Manually insert an expired token
            val expiredToken = "expired-token-12345"
            val now = Clock.System.now()
            val expiredTime = now.minus(2.hours).toLocalDateTime(timeZone)

            transaction(database) {
                PasswordResetTokens.insert {
                    it[PasswordResetTokens.userId] = userId
                    it[token] = expiredToken
                    it[contactValue] = "user@example.com"
                    it[createdAt] = expiredTime
                    it[expiresAt] = expiredTime  // Already expired
                    it[usedAt] = null
                    it[ipAddress] = null
                }
            }

            // Try to verify expired token
            val verifyResult = service.verifyResetToken(expiredToken)
            verifyResult.shouldBeInstanceOf<TokenVerificationResult.Invalid>()

            // Try to consume expired token
            val consumeResult = service.consumeResetToken(expiredToken)
            consumeResult.shouldBeInstanceOf<TokenConsumptionResult.Invalid>()
        }
    }

    context("User Enumeration Prevention") {
        test("non-existent user should return Success (no enumeration)") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            // Request reset for non-existent email
            val result = service.initiatePasswordReset(
                identifier = "nonexistent@example.com",
                contactType = PasswordResetService.ContactType.EMAIL,
                ipAddress = null
            )

            // SECURITY: Should return Success to prevent enumeration
            result.shouldBeInstanceOf<PasswordResetResult.Success>()

            // But no email should be sent
            mockSender.sentTokens.size shouldBe 0

            // And no token should be created
            val tokenCount = transaction(database) {
                PasswordResetTokens.selectAll().count()
            }
            tokenCount shouldBe 0
        }

        test("rate limited user should return Success (no enumeration)") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 1 // Only 1 attempt allowed
                maxAttemptsPerIdentifier = 10
                maxAttemptsPerIp = 10
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val userId = UUID.randomUUID()
            val email = "user@example.com"
            transaction(database) {
                PasswordResetContacts.insert {
                    it[PasswordResetContacts.userId] = userId
                    it[contactType] = "EMAIL"
                    it[contactValue] = email
                    it[createdAt] = Clock.System.now().toLocalDateTime(timeZone)
                    it[updatedAt] = Clock.System.now().toLocalDateTime(timeZone)
                }
            }

            // First attempt - should succeed
            val firstResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            firstResult.shouldBeInstanceOf<PasswordResetResult.Success>()
            mockSender.sentTokens.size shouldBe 1

            // Second attempt - rate limited, but still returns Success
            val secondResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            secondResult.shouldBeInstanceOf<PasswordResetResult.Success>()

            // But no additional email sent (enumeration prevention)
            mockSender.sentTokens.size shouldBe 1
        }
    }

    context("Rate Limiting") {
        test("should enforce identifier rate limit") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 10
                maxAttemptsPerIdentifier = 2 // Only 2 attempts per identifier
                maxAttemptsPerIp = 10
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val email = "user@example.com"

            // First 2 attempts should succeed (even for non-existent user)
            repeat(2) {
                val result = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
                result.shouldBeInstanceOf<PasswordResetResult.Success>()
            }

            // Third attempt should be rate limited
            val thirdResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            thirdResult.shouldBeInstanceOf<PasswordResetResult.RateLimitExceeded>()
        }

        test("should enforce IP rate limit") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 10
                maxAttemptsPerIdentifier = 10
                maxAttemptsPerIp = 2 // Only 2 attempts per IP
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val ipAddress = "192.168.1.100"

            // First 2 attempts should succeed (different emails)
            repeat(2) { i ->
                val result = service.initiatePasswordReset(
                    identifier = "user$i@example.com",
                    contactType = PasswordResetService.ContactType.EMAIL,
                    ipAddress = ipAddress
                )
                result.shouldBeInstanceOf<PasswordResetResult.Success>()
            }

            // Third attempt should be rate limited
            val thirdResult = service.initiatePasswordReset(
                identifier = "user3@example.com",
                contactType = PasswordResetService.ContactType.EMAIL,
                ipAddress = ipAddress
            )
            thirdResult.shouldBeInstanceOf<PasswordResetResult.RateLimitExceeded>()
        }

        test("sender failure should release rate limit reservations") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerUser = 2
                maxAttemptsPerIdentifier = 2
                maxAttemptsPerIp = 2
                rateLimitWindow = 15.minutes
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val userId = UUID.randomUUID()
            val email = "user@example.com"
            transaction(database) {
                PasswordResetContacts.insert {
                    it[PasswordResetContacts.userId] = userId
                    it[contactType] = "EMAIL"
                    it[contactValue] = email
                    it[createdAt] = Clock.System.now().toLocalDateTime(timeZone)
                    it[updatedAt] = Clock.System.now().toLocalDateTime(timeZone)
                }
            }

            // Make sender fail
            mockSender.shouldFail = true

            // First attempt fails due to sender, but returns Success for enumeration prevention
            val firstResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, "192.168.1.1")
            firstResult.shouldBeInstanceOf<PasswordResetResult.Success>()

            // Verify no token was created (send failed)
            val tokenCount1 = transaction(database) {
                PasswordResetTokens.selectAll().count()
            }
            tokenCount1 shouldBe 0

            // Second attempt also fails but returns Success
            val secondResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, "192.168.1.1")
            secondResult.shouldBeInstanceOf<PasswordResetResult.Success>()

            // Fix sender
            mockSender.shouldFail = false

            // Third attempt should succeed with token created (reservations were released on send failures)
            val thirdResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, "192.168.1.1")
            thirdResult.shouldBeInstanceOf<PasswordResetResult.Success>()

            // Verify token WAS created this time
            val tokenCount2 = transaction(database) {
                PasswordResetTokens.selectAll().count()
            }
            tokenCount2 shouldBe 1
        }
    }

    context("Token Management") {
        test("revokeAllResetTokens should invalidate all user tokens") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val userId = UUID.randomUUID()
            val email = "user@example.com"
            transaction(database) {
                PasswordResetContacts.insert {
                    it[PasswordResetContacts.userId] = userId
                    it[contactType] = "EMAIL"
                    it[contactValue] = email
                    it[createdAt] = Clock.System.now().toLocalDateTime(timeZone)
                    it[updatedAt] = Clock.System.now().toLocalDateTime(timeZone)
                }
            }

            // Create multiple tokens
            service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)

            val token1 = mockSender.sentTokens[0].second
            val token2 = mockSender.sentTokens[1].second

            // Both tokens should be valid
            service.verifyResetToken(token1).shouldBeInstanceOf<TokenVerificationResult.Valid>()
            service.verifyResetToken(token2).shouldBeInstanceOf<TokenVerificationResult.Valid>()

            // Revoke all tokens
            service.revokeAllResetTokens(userId)

            // Both tokens should now be invalid
            service.verifyResetToken(token1).shouldBeInstanceOf<TokenVerificationResult.Invalid>()
            service.verifyResetToken(token2).shouldBeInstanceOf<TokenVerificationResult.Invalid>()
        }

        test("consumed token should not be consumable again") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val userId = UUID.randomUUID()
            val email = "user@example.com"
            transaction(database) {
                PasswordResetContacts.insert {
                    it[PasswordResetContacts.userId] = userId
                    it[contactType] = "EMAIL"
                    it[contactValue] = email
                    it[createdAt] = Clock.System.now().toLocalDateTime(timeZone)
                    it[updatedAt] = Clock.System.now().toLocalDateTime(timeZone)
                }
            }

            service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            val token = mockSender.sentTokens.first().second

            // First consumption should succeed
            val firstConsume = service.consumeResetToken(token)
            firstConsume.shouldBeInstanceOf<TokenConsumptionResult.Success>()

            // Second consumption should fail
            val secondConsume = service.consumeResetToken(token)
            secondConsume.shouldBeInstanceOf<TokenConsumptionResult.Invalid>()
        }
    }

    context("Cooldown Period") {
        test("should enforce cooldown period when configured") {
            val config = PasswordResetConfig().apply {
                tokenValidity = 1.hours
                maxAttemptsPerIdentifier = 10
                rateLimitWindow = 15.minutes
                cooldownPeriod = 30.minutes // 30 minute cooldown
                passwordResetSender = mockSender
            }

            val extension = config.build(testContext) as PasswordResetExtension
            val service = extension.passwordResetService

            val email = "user@example.com"

            // First attempt should succeed
            val firstResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            firstResult.shouldBeInstanceOf<PasswordResetResult.Success>()

            // Second attempt within cooldown should be blocked
            val secondResult = service.initiatePasswordReset(email, PasswordResetService.ContactType.EMAIL, null)
            secondResult.shouldBeInstanceOf<PasswordResetResult.RateLimitExceeded>()
            // Verify it's a cooldown-related rate limit (case-insensitive)
            val reason = (secondResult as PasswordResetResult.RateLimitExceeded).reason.lowercase()
            (reason.contains("cooldown") || reason.contains("too soon")).shouldBeTrue()
        }
    }
})
