package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.verification.database.VerifiableContacts
import com.mustafadakhel.kodex.verification.database.VerificationTokens
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import com.mustafadakhel.kodex.extension.AuthenticatedUser
import com.mustafadakhel.kodex.extension.LoginMetadata
import com.mustafadakhel.kodex.model.UserStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * Integration tests for VerificationService.
 *
 * These tests prove the end-to-end verification flow works correctly with:
 * - Database persistence
 * - Token generation and validation
 * - Rate limiting
 * - Contact management
 * - Sender integration
 */
class VerificationServiceIntegrationTest : FunSpec({

    // Test database setup
    val database = Database.connect("jdbc:h2:mem:test_verification_integration;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
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
    class MockVerificationSender : VerificationSender {
        val sentTokens = mutableListOf<Pair<String, String>>() // (destination, token)
        var shouldFail = false

        override suspend fun send(destination: String, token: String) {
            if (shouldFail) {
                throw RuntimeException("Sender failure simulation")
            }
            sentTokens.add(destination to token)
        }

        fun reset() {
            sentTokens.clear()
            shouldFail = false
        }
    }

    val mockSender = MockVerificationSender()

    beforeTest {
        // Create tables
        transaction(database) {
            SchemaUtils.create(VerifiableContacts, VerificationTokens)
        }
        mockSender.reset()
    }

    afterTest {
        // Drop tables
        transaction(database) {
            SchemaUtils.drop(VerifiableContacts, VerificationTokens)
        }
    }

    context("End-to-End Verification Flow") {
        test("complete verification flow: send → verify → verified") {
            // Setup: Create verification service with email policy
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    required = false
                    autoSend = false
                    sender = mockSender
                }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val email = "user@example.com"
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)

            // Step 1: Set email contact
            verificationService.setEmail(userId, email)

            // Verify contact exists but is not verified
            val contact = verificationService.getContact(userId, emailIdentifier)
            contact.shouldNotBeNull()
            contact.contactValue shouldBe email
            contact.isVerified.shouldBeFalse()
            contact.verifiedAt.shouldBeNull()

            verificationService.isContactVerified(userId, emailIdentifier).shouldBeFalse()

            // Step 2: Send verification token
            val sendResult = verificationService.sendVerification(userId, emailIdentifier)
            sendResult.shouldBeInstanceOf<VerificationSendResult.Success>()

            // Verify token was sent
            mockSender.sentTokens shouldHaveSize 1
            val (sentDestination, sentToken) = mockSender.sentTokens.first()
            sentDestination shouldBe email
            sentToken.shouldNotBeNull()

            // Verify token exists in database
            val tokenInDb = transaction(database) {
                VerificationTokens
                    .selectAll()
                    .where { VerificationTokens.userId eq userId }
                    .singleOrNull()
            }
            tokenInDb.shouldNotBeNull()

            // Step 3: Verify the token
            val verifyResult = verificationService.verifyToken(userId, emailIdentifier, sentToken)
            verifyResult.shouldBeInstanceOf<VerificationResult.Success>()

            // Step 4: Verify contact is now marked as verified
            verificationService.isContactVerified(userId, emailIdentifier).shouldBeTrue()

            val verifiedContact = verificationService.getContact(userId, emailIdentifier)
            verifiedContact.shouldNotBeNull()
            verifiedContact.isVerified.shouldBeTrue()
            verifiedContact.verifiedAt.shouldNotBeNull()

            // Step 5: Verify token is marked as used
            val usedToken = transaction(database) {
                VerificationTokens
                    .selectAll()
                    .where { VerificationTokens.userId eq userId }
                    .singleOrNull()
            }
            usedToken.shouldNotBeNull()
            usedToken[VerificationTokens.usedAt].shouldNotBeNull()
        }

        test("invalid token should fail verification") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email {
                    sender = mockSender
                }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            verificationService.setEmail(userId, "user@example.com")

            // Send verification
            verificationService.sendVerification(userId, ContactIdentifier(ContactType.EMAIL))

            // Try to verify with wrong token
            val result = verificationService.verifyToken(
                userId,
                ContactIdentifier(ContactType.EMAIL),
                "invalid-token-12345678901234567890123456789012"
            )

            result.shouldBeInstanceOf<VerificationResult.Invalid>()
            verificationService.isContactVerified(userId, ContactIdentifier(ContactType.EMAIL)).shouldBeFalse()
        }

        test("expired token should fail verification") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    sender = mockSender
                }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "user@example.com")

            // Manually insert an expired token
            val expiredToken = "expired-token-12345"
            val now = kotlinx.datetime.Clock.System.now()
            val expiredTime = now.minus(2.hours).toLocalDateTime(timeZone)

            transaction(database) {
                VerificationTokens.insert {
                    it[VerificationTokens.userId] = userId
                    it[contactType] = ContactType.EMAIL
                    it[customAttributeKey] = null
                    it[token] = expiredToken
                    it[createdAt] = expiredTime
                    it[expiresAt] = expiredTime  // Already expired
                    it[usedAt] = null
                }
            }

            // Try to verify with expired token
            val result = verificationService.verifyToken(userId, emailIdentifier, expiredToken)

            result.shouldBeInstanceOf<VerificationResult.Invalid>()
        }
    }

    context("Contact Management") {
        test("setContact should create new contact") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val email = "test@example.com"

            verificationService.setEmail(userId, email)

            val contact = verificationService.getContact(userId, ContactIdentifier(ContactType.EMAIL))
            contact.shouldNotBeNull()
            contact.contactValue shouldBe email
            contact.isVerified.shouldBeFalse()
        }

        test("updating contact value should reset verification status") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)

            // Set initial email and verify it
            verificationService.setEmail(userId, "old@example.com")
            verificationService.sendVerification(userId, emailIdentifier)
            val oldToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, emailIdentifier, oldToken)

            verificationService.isContactVerified(userId, emailIdentifier).shouldBeTrue()

            // Change email to new value
            verificationService.setEmail(userId, "new@example.com")

            // Verification status should be reset
            verificationService.isContactVerified(userId, emailIdentifier).shouldBeFalse()
            val contact = verificationService.getContact(userId, emailIdentifier)
            contact.shouldNotBeNull()
            contact.contactValue shouldBe "new@example.com"
            contact.isVerified.shouldBeFalse()
            contact.verifiedAt.shouldBeNull()
        }

        test("removing contact should delete contact and tokens") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)

            // Set email and send verification
            verificationService.setEmail(userId, "user@example.com")
            verificationService.sendVerification(userId, emailIdentifier)

            // Verify contact and token exist
            verificationService.getContact(userId, emailIdentifier).shouldNotBeNull()
            transaction(database) {
                VerificationTokens.selectAll().where { VerificationTokens.userId eq userId }.count()
            } shouldBe 1

            // Remove contact
            verificationService.removeContact(userId, emailIdentifier)

            // Verify contact and tokens are deleted
            verificationService.getContact(userId, emailIdentifier).shouldBeNull()
            transaction(database) {
                VerificationTokens.selectAll().where { VerificationTokens.userId eq userId }.count()
            } shouldBe 0
        }

        test("getUserContacts should return all contacts for user") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()

            // Initially no contacts
            verificationService.getUserContacts(userId).shouldBeEmpty()

            // Add multiple contacts
            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")
            verificationService.setCustomAttribute(userId, "discord", "user#1234")

            // Should return all 3 contacts
            val contacts = verificationService.getUserContacts(userId)
            contacts shouldHaveSize 3

            val contactTypes = contacts.map { it.identifier.type }
            contactTypes shouldBe listOf(ContactType.EMAIL, ContactType.PHONE, ContactType.CUSTOM_ATTRIBUTE)
        }
    }

    context("Rate Limiting Integration") {
        test("rate limit should be enforced across send operations") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                maxSendAttemptsPerUser = 3
                email { sender = mockSender }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "user@example.com")

            // First 3 attempts should succeed
            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, emailIdentifier)
                result.shouldBeInstanceOf<VerificationSendResult.Success>()
            }

            // 4th attempt should be rate limited
            val result = verificationService.sendVerification(userId, emailIdentifier)
            result.shouldBeInstanceOf<VerificationSendResult.RateLimitExceeded>()

            // Only 3 tokens should have been sent
            mockSender.sentTokens shouldHaveSize 3
        }

        test("sender failure should release rate limit reservation") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                maxSendAttemptsPerUser = 3
                email { sender = mockSender }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "user@example.com")

            // Make sender fail
            mockSender.shouldFail = true

            // 3 failed attempts (should not count against rate limit)
            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, emailIdentifier)
                // Should return success even though sender failed (based on implementation)
                // The rate limit reservation should be released
            }

            // Disable failure
            mockSender.shouldFail = false

            // Next 3 attempts should still succeed (failures didn't count)
            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, emailIdentifier)
                result.shouldBeInstanceOf<VerificationSendResult.Success>()
            }

            // 4th successful attempt should be rate limited
            val result = verificationService.sendVerification(userId, emailIdentifier)
            result.shouldBeInstanceOf<VerificationSendResult.RateLimitExceeded>()

            // Only 3 tokens should have been successfully sent
            mockSender.sentTokens shouldHaveSize 3
        }
    }

    context("Verification Status") {
        test("canLogin should return true when all required contacts verified") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY
                email {
                    required = true
                    sender = mockSender
                }
                phone {
                    required = false
                    sender = mockSender
                }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()

            // Set both contacts
            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            // Initially can't login (email not verified)
            verificationService.canLogin(userId).shouldBeFalse()

            // Verify email (required)
            verificationService.sendVerification(userId, ContactIdentifier(ContactType.EMAIL))
            val emailToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactIdentifier(ContactType.EMAIL), emailToken)

            // Now can login (required contact verified, phone is optional)
            verificationService.canLogin(userId).shouldBeTrue()
        }

        test("getMissingVerifications should return unverified required contacts") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY
                email {
                    required = true
                    sender = mockSender
                }
                phone {
                    required = true
                    sender = mockSender
                }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()

            // Set both contacts
            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            // Both should be missing
            val missing = verificationService.getMissingVerifications(userId)
            missing shouldHaveSize 2

            // Verify email
            verificationService.sendVerification(userId, ContactIdentifier(ContactType.EMAIL))
            val emailToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactIdentifier(ContactType.EMAIL), emailToken)

            // Only phone should be missing now
            val stillMissing = verificationService.getMissingVerifications(userId)
            stillMissing shouldHaveSize 1
            stillMissing.first().type shouldBe ContactType.PHONE
        }

        test("getStatus should return complete verification status") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            verificationService.setEmail(userId, "user@example.com")

            val status = verificationService.getStatus(userId)
            status.userId shouldBe userId
            status.contacts.size shouldBe 1

            // Verify service methods work
            verificationService.canLogin(userId).shouldBeTrue() // MANUAL strategy
            verificationService.getMissingVerifications(userId).shouldBeEmpty()
        }
    }

    context("Manual Control") {
        test("setVerified should manually mark contact as verified") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
            }

            val service = config.build(testContext) as VerificationExtension
            val verificationService = service.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)

            verificationService.setEmail(userId, "user@example.com")
            verificationService.isContactVerified(userId, emailIdentifier).shouldBeFalse()

            // Manually mark as verified (e.g., by admin)
            verificationService.setVerified(userId, emailIdentifier, true)

            verificationService.isContactVerified(userId, emailIdentifier).shouldBeTrue()

            // Can also manually unverify
            verificationService.setVerified(userId, emailIdentifier, false)
            verificationService.isContactVerified(userId, emailIdentifier).shouldBeFalse()
        }
    }

    context("Login Blocking") {
        test("afterAuthentication should throw UnverifiedAccount when required contact not verified") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY
                email {
                    required = true
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userId = UUID.randomUUID()
            verificationService.setEmail(userId, "unverified@example.com")

            // canLogin should return false
            verificationService.canLogin(userId).shouldBeFalse()

            // Simulate the afterAuthentication check with data classes
            val authenticatedUser = AuthenticatedUser(
                userId = userId,
                email = "unverified@example.com",
                phone = null,
                roles = listOf("owner"),
                status = UserStatus.ACTIVE
            )

            val loginMetadata = LoginMetadata(
                ipAddress = "127.0.0.1",
                userAgent = "Test"
            )

            // The extension should throw UnverifiedAccount
            shouldThrow<VerificationThrowable.UnverifiedAccount> {
                extension.afterAuthentication(authenticatedUser, loginMetadata)
            }
        }

        test("afterAuthentication should succeed when required contact is verified") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.VERIFY_REQUIRED_ONLY
                email {
                    required = true
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "verified@example.com")

            // Verify the email
            verificationService.sendVerification(userId, emailIdentifier)
            val token = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, emailIdentifier, token)

            // canLogin should return true
            verificationService.canLogin(userId).shouldBeTrue()

            // The extension should NOT throw
            val authenticatedUser = AuthenticatedUser(
                userId = userId,
                email = "verified@example.com",
                phone = null,
                roles = listOf("owner"),
                status = UserStatus.ACTIVE
            )

            val loginMetadata = LoginMetadata(
                ipAddress = "127.0.0.1",
                userAgent = "Test"
            )

            // Should not throw
            extension.afterAuthentication(authenticatedUser, loginMetadata)
        }

        test("MANUAL strategy allows login when no contacts are marked as required") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email {
                    // In MANUAL mode, required=false (default) means verification is optional
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userId = UUID.randomUUID()
            verificationService.setEmail(userId, "unverified@example.com")

            // canLogin should return true when no contacts are required
            verificationService.canLogin(userId).shouldBeTrue()

            val authenticatedUser = AuthenticatedUser(
                userId = userId,
                email = "unverified@example.com",
                phone = null,
                roles = listOf("owner"),
                status = UserStatus.ACTIVE
            )

            val loginMetadata = LoginMetadata(
                ipAddress = "127.0.0.1",
                userAgent = "Test"
            )

            // Should not throw when no contacts are required
            extension.afterAuthentication(authenticatedUser, loginMetadata)
        }
    }

    context("Resend Verification") {
        test("resendVerification should invalidate old token and send new one") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "user@example.com")

            // Send initial verification
            val initialResult = verificationService.sendVerification(userId, emailIdentifier)
            initialResult.shouldBeInstanceOf<VerificationSendResult.Success>()
            val initialToken = mockSender.sentTokens.first().second

            // Resend verification
            val resendResult = verificationService.resendVerification(userId, emailIdentifier)
            resendResult.shouldBeInstanceOf<VerificationSendResult.Success>()
            mockSender.sentTokens shouldHaveSize 2
            val newToken = mockSender.sentTokens.last().second

            // Old token should be deleted (not work)
            val oldTokenResult = verificationService.verifyToken(userId, emailIdentifier, initialToken)
            oldTokenResult.shouldBeInstanceOf<VerificationResult.Invalid>()

            // New token should work
            val newTokenResult = verificationService.verifyToken(userId, emailIdentifier, newToken)
            newTokenResult.shouldBeInstanceOf<VerificationResult.Success>()
            verificationService.isContactVerified(userId, emailIdentifier).shouldBeTrue()
        }

        test("resendVerification should respect rate limits") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                maxSendAttemptsPerUser = 3
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "user@example.com")

            // First send + 2 resends = 3 total (at limit)
            verificationService.sendVerification(userId, emailIdentifier)
            verificationService.resendVerification(userId, emailIdentifier)
            verificationService.resendVerification(userId, emailIdentifier)

            // 4th attempt should be rate limited
            val result = verificationService.resendVerification(userId, emailIdentifier)
            result.shouldBeInstanceOf<VerificationSendResult.RateLimitExceeded>()

            mockSender.sentTokens shouldHaveSize 3
        }
    }

    context("Security - Cross-User Token Theft Prevention") {
        test("verification token should be bound to specific user") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)

            // Setup both users with email contacts
            verificationService.setEmail(userA, "usera@example.com")
            verificationService.setEmail(userB, "userb@example.com")

            // Send verification to User A
            verificationService.sendVerification(userA, emailIdentifier)
            val tokenForUserA = mockSender.sentTokens.first().second

            // User B tries to use User A's token - should fail
            val attackResult = verificationService.verifyToken(userB, emailIdentifier, tokenForUserA)
            attackResult.shouldBeInstanceOf<VerificationResult.Invalid>()
            (attackResult as VerificationResult.Invalid).reason shouldContain "not found"

            // User B should NOT be verified
            verificationService.isContactVerified(userB, emailIdentifier).shouldBeFalse()

            // User A should NOT be verified either (token not consumed by attack)
            verificationService.isContactVerified(userA, emailIdentifier).shouldBeFalse()

            // User A can still use their own token
            val legitResult = verificationService.verifyToken(userA, emailIdentifier, tokenForUserA)
            legitResult.shouldBeInstanceOf<VerificationResult.Success>()
            verificationService.isContactVerified(userA, emailIdentifier).shouldBeTrue()
        }

        test("token cannot be reused after successful verification") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            val verificationService = extension.verificationService

            val userId = UUID.randomUUID()
            val emailIdentifier = ContactIdentifier(ContactType.EMAIL)
            verificationService.setEmail(userId, "user@example.com")

            // Send and verify
            verificationService.sendVerification(userId, emailIdentifier)
            val token = mockSender.sentTokens.first().second

            val firstResult = verificationService.verifyToken(userId, emailIdentifier, token)
            firstResult.shouldBeInstanceOf<VerificationResult.Success>()

            // Try to use the same token again - should fail
            val secondResult = verificationService.verifyToken(userId, emailIdentifier, token)
            secondResult.shouldBeInstanceOf<VerificationResult.Invalid>()
            (secondResult as VerificationResult.Invalid).reason shouldContain "used"
        }
    }
})
