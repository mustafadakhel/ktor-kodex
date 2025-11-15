package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import com.mustafadakhel.kodex.util.kodexTransaction
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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
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

    val database = Database.connect("jdbc:h2:mem:test_verification_integration;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    val timeZone = TimeZone.UTC

    val mockEventBus = object : EventBus {
        override suspend fun publish(event: KodexEvent) {}
        override fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>) {}
        override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {}
        override fun shutdown() {}
    }

    val testContext = object : ExtensionContext {
        override val realm = Realm(owner = "test-realm")
        override val timeZone = timeZone
        override val eventBus = mockEventBus
        override val rateLimiter = com.mustafadakhel.kodex.ratelimit.inmemory.InMemoryRateLimiter()
    }

    // Mock sender that tracks calls
    class MockVerificationSender : VerificationSender {
        val sentTokens = mutableListOf<Pair<String, String>>() // (contactValue, token)
        var shouldFail = false

        override suspend fun send(contactValue: String, token: String) {
            if (shouldFail) {
                throw RuntimeException("Sender failure simulation")
            }
            sentTokens.add(contactValue to token)
        }

        fun reset() {
            sentTokens.clear()
            shouldFail = false
        }
    }

    val mockSender = MockVerificationSender()

    beforeTest {
        TestDatabaseSetup.setupTestEngine(database)
        kodexTransaction {
            SchemaUtils.create(VerifiableContacts, VerificationTokens)
        }
        mockSender.reset()
        // Clear rate limiter state between tests
        testContext.rateLimiter.clearAll()
    }

    afterTest {
        kodexTransaction {
            SchemaUtils.drop(VerifiableContacts, VerificationTokens)
        }
    }

    context("End-to-End Verification Flow") {
        test("complete verification flow: send → verify → verified") {
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

            verificationService.setEmail(userId, email)
            val contact = verificationService.getContact(userId, emailIdentifier)
            contact.shouldNotBeNull()
            contact.contactValue shouldBe email
            contact.isVerified.shouldBeFalse()
            contact.verifiedAt.shouldBeNull()

            verificationService.isContactVerified(userId, emailIdentifier).shouldBeFalse()

            val sendResult = verificationService.sendVerification(userId, emailIdentifier)
            sendResult.shouldBeInstanceOf<VerificationSendResult.Success>()

            mockSender.sentTokens shouldHaveSize 1
            val (sentDestination, sentToken) = mockSender.sentTokens.first()
            sentDestination shouldBe email
            sentToken.shouldNotBeNull()

            val tokenInDb = kodexTransaction {
                VerificationTokens
                    .selectAll()
                    .where { VerificationTokens.userId eq userId }
                    .singleOrNull()
            }
            tokenInDb.shouldNotBeNull()

            val verifyResult = verificationService.verifyToken(userId, emailIdentifier, sentToken)
            verifyResult.shouldBeInstanceOf<VerificationResult.Success>()

            verificationService.isContactVerified(userId, emailIdentifier).shouldBeTrue()

            val verifiedContact = verificationService.getContact(userId, emailIdentifier)
            verifiedContact.shouldNotBeNull()
            verifiedContact.isVerified.shouldBeTrue()
            verifiedContact.verifiedAt.shouldNotBeNull()

            val usedToken = kodexTransaction {
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

            verificationService.sendVerification(userId, ContactIdentifier(ContactType.EMAIL))
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

            val expiredToken = "expired-token-12345"
            val now = kotlinx.datetime.Clock.System.now()
            val expiredTime = now.minus(2.hours).toLocalDateTime(timeZone)

            kodexTransaction {
                VerificationTokens.insert {
                    it[VerificationTokens.realmId] = "test-realm"
                    it[VerificationTokens.userId] = userId
                    it[contactType] = ContactType.EMAIL
                    it[customAttributeKey] = null
                    it[token] = expiredToken
                    it[createdAt] = expiredTime
                    it[expiresAt] = expiredTime  // Already expired
                    it[usedAt] = null
                }
            }

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

            verificationService.setEmail(userId, "new@example.com")
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

            verificationService.setEmail(userId, "user@example.com")
            verificationService.sendVerification(userId, emailIdentifier)
            verificationService.getContact(userId, emailIdentifier).shouldNotBeNull()
            kodexTransaction {
                VerificationTokens.selectAll().where { VerificationTokens.userId eq userId }.count()
            } shouldBe 1

            verificationService.removeContact(userId, emailIdentifier)
            verificationService.getContact(userId, emailIdentifier).shouldBeNull()
            kodexTransaction {
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

            verificationService.getUserContacts(userId).shouldBeEmpty()

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")
            verificationService.setCustomAttribute(userId, "discord", "user#1234")

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

            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, emailIdentifier)
                result.shouldBeInstanceOf<VerificationSendResult.Success>()
            }

            val result = verificationService.sendVerification(userId, emailIdentifier)
            result.shouldBeInstanceOf<VerificationSendResult.RateLimitExceeded>()

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

            mockSender.shouldFail = true

            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, emailIdentifier)
                // Should return success even though sender failed (based on implementation)
                // The rate limit reservation should be released
            }

            mockSender.shouldFail = false

            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, emailIdentifier)
                result.shouldBeInstanceOf<VerificationSendResult.Success>()
            }

            val result = verificationService.sendVerification(userId, emailIdentifier)
            result.shouldBeInstanceOf<VerificationSendResult.RateLimitExceeded>()

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

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            verificationService.canLogin(userId).shouldBeFalse()
            verificationService.sendVerification(userId, ContactIdentifier(ContactType.EMAIL))
            val emailToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactIdentifier(ContactType.EMAIL), emailToken)

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

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            val missing = verificationService.getMissingVerifications(userId)
            missing shouldHaveSize 2

            verificationService.sendVerification(userId, ContactIdentifier(ContactType.EMAIL))
            val emailToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactIdentifier(ContactType.EMAIL), emailToken)
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

            verificationService.setVerified(userId, emailIdentifier, true)

            verificationService.isContactVerified(userId, emailIdentifier).shouldBeTrue()
            verificationService.setVerified(userId, emailIdentifier, false)
            verificationService.isContactVerified(userId, emailIdentifier).shouldBeFalse()
        }
    }
})
