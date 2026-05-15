package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.extension.ExtensionContext
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.tokens.token.TokenHasher
import com.mustafadakhel.kodex.verification.schema.VerificationSchema
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
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

    val timeZone = TimeZone.UTC

    val mockEventBus = object : EventBus {
        override suspend fun publish(event: KodexEvent) {}
        override fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>) {}
        override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {}
        override fun shutdown() {}
    }

    lateinit var db: KodexDatabase
    lateinit var verificationSchema: VerificationSchema
    lateinit var testSetup: com.mustafadakhel.kodex.test.TestDatabaseSetup
    var userCounter = 0

    val testContext = object : ExtensionContext {
        override val realm = Realm(name = "test-realm")
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
        val database = Database.connect(
            "jdbc:h2:mem:test_verification_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val core = CoreSchema("test_")
        verificationSchema = VerificationSchema(core)
        db = KodexDatabase(database, core, mapOf(VerificationSchema::class to verificationSchema))
        db.createSchema()
        testSetup = com.mustafadakhel.kodex.test.TestDatabaseSetup(db)
        userCounter = 0
        mockSender.reset()
        // Clear rate limiter state between tests
        testContext.rateLimiter.clearAll()
    }

    afterTest {
        db.transaction {
            SchemaUtils.drop(
                verificationSchema.verificationTokens,
                verificationSchema.verifiableContacts,
                *db.core.tables().reversed().toTypedArray()
            )
        }
    }

    context("End-to-End Verification Flow") {
        test("complete verification flow: send -> verify -> verified") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    required = false
                    autoSend = false
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            val email = "user@example.com"

            verificationService.setEmail(userId, email)
            val contact = verificationService.getContact(userId, ContactType.Email)
            contact.shouldNotBeNull()
            contact.contactValue shouldBe email
            contact.isVerified.shouldBeFalse()
            contact.verifiedAt.shouldBeNull()

            verificationService.isContactVerified(userId, ContactType.Email).shouldBeFalse()

            val sendResult = verificationService.sendVerification(userId, ContactType.Email)
            sendResult.shouldBeInstanceOf<VerificationSendResult.Success>()

            mockSender.sentTokens shouldHaveSize 1
            val (sentDestination, sentToken) = mockSender.sentTokens.first()
            sentDestination shouldBe email
            sentToken.shouldNotBeNull()

            val tokens = verificationSchema.verificationTokens
            val tokenInDb = db.transaction {
                tokens
                    .selectAll()
                    .where { tokens.userId eq userId }
                    .singleOrNull()
            }
            tokenInDb.shouldNotBeNull()

            val verifyResult = verificationService.verifyToken(userId, ContactType.Email, sentToken)
            verifyResult.shouldBeInstanceOf<VerificationResult.Success>()

            verificationService.isContactVerified(userId, ContactType.Email).shouldBeTrue()

            val verifiedContact = verificationService.getContact(userId, ContactType.Email)
            verifiedContact.shouldNotBeNull()
            verifiedContact.isVerified.shouldBeTrue()
            verifiedContact.verifiedAt.shouldNotBeNull()

            val usedToken = db.transaction {
                tokens
                    .selectAll()
                    .where { tokens.userId eq userId }
                    .singleOrNull()
            }
            usedToken.shouldNotBeNull()
            usedToken[tokens.usedAt].shouldNotBeNull()
        }

        test("invalid token should fail verification") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email {
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            verificationService.sendVerification(userId, ContactType.Email)
            val result = verificationService.verifyToken(
                userId,
                ContactType.Email,
                "invalid-token-12345678901234567890123456789012"
            )

            result.shouldBeInstanceOf<VerificationResult.Invalid>()
            verificationService.isContactVerified(userId, ContactType.Email).shouldBeFalse()
        }

        test("expired token should fail verification") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            val expiredToken = "expired-token-12345"
            val now = com.mustafadakhel.kodex.util.CurrentKotlinInstant
            val expiredTime = now.minus(2.hours).toLocalDateTime(timeZone)

            val tokens = verificationSchema.verificationTokens
            db.transaction {
                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[contactType] = "email"
                    it[token] = TokenHasher.hash(expiredToken)
                    it[createdAt] = expiredTime
                    it[expiresAt] = expiredTime  // Already expired
                    it[usedAt] = null
                }
            }

            val result = verificationService.verifyToken(userId, ContactType.Email, expiredToken)

            result.shouldBeInstanceOf<VerificationResult.Invalid>()
        }

        test("phone default token format should be 6-digit numeric") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                phone {
                    required = false
                    autoSend = false
                    sender = mockSender
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setPhone(userId, "+1234567890")
            verificationService.sendVerification(userId, ContactType.Phone)

            mockSender.sentTokens shouldHaveSize 1
            val token = mockSender.sentTokens.first().second
            token.matches(Regex("\\d{6}")) shouldBe true
        }
    }

    context("Contact Management") {
        test("setContact should create new contact") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            val email = "test@example.com"

            verificationService.setEmail(userId, email)

            val contact = verificationService.getContact(userId, ContactType.Email)
            contact.shouldNotBeNull()
            contact.contactValue shouldBe email
            contact.isVerified.shouldBeFalse()
        }

        test("updating contact value should reset verification status") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            // Set initial email and verify it
            verificationService.setEmail(userId, "old@example.com")
            verificationService.sendVerification(userId, ContactType.Email)
            val oldToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactType.Email, oldToken)

            verificationService.isContactVerified(userId, ContactType.Email).shouldBeTrue()

            verificationService.setEmail(userId, "new@example.com")
            verificationService.isContactVerified(userId, ContactType.Email).shouldBeFalse()
            val contact = verificationService.getContact(userId, ContactType.Email)
            contact.shouldNotBeNull()
            contact.contactValue shouldBe "new@example.com"
            contact.isVerified.shouldBeFalse()
            contact.verifiedAt.shouldBeNull()
        }

        test("changing contact value should invalidate pending tokens") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "old@example.com")
            verificationService.sendVerification(userId, ContactType.Email)
            val oldToken = mockSender.sentTokens.last().second

            val tokens = verificationSchema.verificationTokens
            val pendingTokenCount = db.transaction {
                tokens.selectAll()
                    .where { (tokens.userId eq userId) and (tokens.realmId eq "test-realm") }
                    .count()
            }
            pendingTokenCount shouldBe 1

            verificationService.setEmail(userId, "new@example.com")

            val remainingTokenCount = db.transaction {
                tokens.selectAll()
                    .where { (tokens.userId eq userId) and (tokens.realmId eq "test-realm") }
                    .count()
            }
            remainingTokenCount shouldBe 0

            val result = verificationService.verifyToken(userId, ContactType.Email, oldToken)
            result.shouldBeInstanceOf<VerificationResult.Invalid>()
        }

        test("removing contact should delete contact and tokens") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "user@example.com")
            verificationService.sendVerification(userId, ContactType.Email)
            verificationService.getContact(userId, ContactType.Email).shouldNotBeNull()

            val tokens = verificationSchema.verificationTokens
            db.transaction {
                tokens.selectAll().where { tokens.userId eq userId }.count()
            } shouldBe 1

            verificationService.removeContact(userId, ContactType.Email)
            verificationService.getContact(userId, ContactType.Email).shouldBeNull()
            db.transaction {
                tokens.selectAll().where { tokens.userId eq userId }.count()
            } shouldBe 0
        }

        test("getUserContacts should return all contacts for user") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.getUserContacts(userId).shouldBeEmpty()

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")
            verificationService.setCustomAttribute(userId, "discord", "user#1234")

            val contacts = verificationService.getUserContacts(userId)
            contacts shouldHaveSize 3

            val contactTypes = contacts.map { it.contactType }
            contactTypes shouldBe listOf(ContactType.Email, ContactType.Phone, ContactType.CustomAttribute("discord"))
        }
    }

    context("Dependency Enforcement") {
        test("sending verification for dependent channel without verified dependency returns DependencyNotMet") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    required = false
                    autoSend = false
                    sender = mockSender
                }
                phone {
                    required = false
                    autoSend = false
                    sender = mockSender
                    dependsOn(ContactType.Email)
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            val result = verificationService.sendVerification(userId, ContactType.Phone)
            result.shouldBeInstanceOf<VerificationSendResult.DependencyNotMet>()
            result.missingDependencies shouldHaveSize 1
            result.missingDependencies.first() shouldBe ContactType.Email
        }

        test("resend blocked when dependency becomes unverified after contact change") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    required = false
                    autoSend = false
                    sender = mockSender
                }
                phone {
                    required = false
                    autoSend = false
                    sender = mockSender
                    dependsOn(ContactType.Email)
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            // Set up and verify email
            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")
            verificationService.sendVerification(userId, ContactType.Email)
            val emailToken = mockSender.sentTokens.last().second
            verificationService.verifyToken(userId, ContactType.Email, emailToken)

            // Phone send succeeds while email is verified
            val firstSend = verificationService.sendVerification(userId, ContactType.Phone)
            firstSend.shouldBeInstanceOf<VerificationSendResult.Success>()

            // Change email -> un-verifies email, deletes phone tokens
            verificationService.setEmail(userId, "new@example.com")

            // Phone send now blocked because email dependency is no longer verified
            val resend = verificationService.sendVerification(userId, ContactType.Phone)
            resend.shouldBeInstanceOf<VerificationSendResult.DependencyNotMet>()
        }

        test("sending verification for dependent channel with verified dependency succeeds") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                defaultTokenExpiration = 1.hours
                email {
                    required = false
                    autoSend = false
                    sender = mockSender
                }
                phone {
                    required = false
                    autoSend = false
                    sender = mockSender
                    dependsOn(ContactType.Email)
                }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            verificationService.sendVerification(userId, ContactType.Email)
            val emailToken = mockSender.sentTokens.last().second
            verificationService.verifyToken(userId, ContactType.Email, emailToken)

            val result = verificationService.sendVerification(userId, ContactType.Phone)
            result.shouldBeInstanceOf<VerificationSendResult.Success>()
        }
    }

    context("Rate Limiting Integration") {
        test("rate limit should be enforced across send operations") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                maxSendAttemptsPerUser = 3
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, ContactType.Email)
                result.shouldBeInstanceOf<VerificationSendResult.Success>()
            }

            val result = verificationService.sendVerification(userId, ContactType.Email)
            result.shouldBeInstanceOf<VerificationSendResult.RateLimitExceeded>()

            mockSender.sentTokens shouldHaveSize 3
        }

        test("sender failure should release rate limit reservation") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                maxSendAttemptsPerUser = 3
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            mockSender.shouldFail = true

            for (i in 1..3) {
                verificationService.sendVerification(userId, ContactType.Email)
            }

            mockSender.shouldFail = false

            for (i in 1..3) {
                val result = verificationService.sendVerification(userId, ContactType.Email)
                result.shouldBeInstanceOf<VerificationSendResult.Success>()
            }

            val result = verificationService.sendVerification(userId, ContactType.Email)
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

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            verificationService.canLogin(userId).shouldBeFalse()
            verificationService.sendVerification(userId, ContactType.Email)
            val emailToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactType.Email, emailToken)

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

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "user@example.com")
            verificationService.setPhone(userId, "+1234567890")

            val missing = verificationService.getMissingVerifications(userId)
            missing shouldHaveSize 2

            verificationService.sendVerification(userId, ContactType.Email)
            val emailToken = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactType.Email, emailToken)
            val stillMissing = verificationService.getMissingVerifications(userId)
            stillMissing shouldHaveSize 1
            stillMissing.first() shouldBe ContactType.Phone
        }

        test("getStatus should return complete verification status") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
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

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")

            verificationService.setEmail(userId, "user@example.com")
            verificationService.isContactVerified(userId, ContactType.Email).shouldBeFalse()

            verificationService.setVerified(userId, ContactType.Email, true)

            verificationService.isContactVerified(userId, ContactType.Email).shouldBeTrue()
            verificationService.setVerified(userId, ContactType.Email, false)
            verificationService.isContactVerified(userId, ContactType.Email).shouldBeFalse()
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
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
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
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "verified@example.com")

            // Verify the email
            verificationService.sendVerification(userId, ContactType.Email)
            val token = mockSender.sentTokens.first().second
            verificationService.verifyToken(userId, ContactType.Email, token)

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
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
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
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            // Send initial verification
            val initialResult = verificationService.sendVerification(userId, ContactType.Email)
            initialResult.shouldBeInstanceOf<VerificationSendResult.Success>()
            val initialToken = mockSender.sentTokens.first().second

            // Resend verification
            val resendResult = verificationService.resendVerification(userId, ContactType.Email)
            resendResult.shouldBeInstanceOf<VerificationSendResult.Success>()
            mockSender.sentTokens shouldHaveSize 2
            val newToken = mockSender.sentTokens.last().second

            // Old token should be deleted (not work)
            val oldTokenResult = verificationService.verifyToken(userId, ContactType.Email, initialToken)
            oldTokenResult.shouldBeInstanceOf<VerificationResult.Invalid>()

            // New token should work
            val newTokenResult = verificationService.verifyToken(userId, ContactType.Email, newToken)
            newTokenResult.shouldBeInstanceOf<VerificationResult.Success>()
            verificationService.isContactVerified(userId, ContactType.Email).shouldBeTrue()
        }

        test("resendVerification should respect rate limits") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                maxSendAttemptsPerUser = 3
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            // First send + 2 resends = 3 total (at limit)
            verificationService.sendVerification(userId, ContactType.Email)
            verificationService.resendVerification(userId, ContactType.Email)
            verificationService.resendVerification(userId, ContactType.Email)

            // 4th attempt should be rate limited
            val result = verificationService.resendVerification(userId, ContactType.Email)
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
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userA = testSetup.createTestUser(email = "userA_${++userCounter}@test.com", realmId = "test-realm")
            val userB = testSetup.createTestUser(email = "userB_${++userCounter}@test.com", realmId = "test-realm")

            // Setup both users with email contacts
            verificationService.setEmail(userA, "usera@example.com")
            verificationService.setEmail(userB, "userb@example.com")

            // Send verification to User A
            verificationService.sendVerification(userA, ContactType.Email)
            val tokenForUserA = mockSender.sentTokens.first().second

            // User B tries to use User A's token - should fail
            val attackResult = verificationService.verifyToken(userB, ContactType.Email, tokenForUserA)
            attackResult.shouldBeInstanceOf<VerificationResult.Invalid>()
            (attackResult as VerificationResult.Invalid).reason shouldContain "not found"

            // User B should NOT be verified
            verificationService.isContactVerified(userB, ContactType.Email).shouldBeFalse()

            // User A should NOT be verified either (token not consumed by attack)
            verificationService.isContactVerified(userA, ContactType.Email).shouldBeFalse()

            // User A can still use their own token
            val legitResult = verificationService.verifyToken(userA, ContactType.Email, tokenForUserA)
            legitResult.shouldBeInstanceOf<VerificationResult.Success>()
            verificationService.isContactVerified(userA, ContactType.Email).shouldBeTrue()
        }

        test("token cannot be reused after successful verification") {
            val config = VerificationConfig().apply {
                strategy = VerificationConfig.VerificationStrategy.MANUAL
                email { sender = mockSender }
            }

            val extension = config.build(testContext) as VerificationExtension
            extension.initialize(db)
            val verificationService = extension.verificationService

            val userId = testSetup.createTestUser(email = "testuser${++userCounter}@test.com", realmId = "test-realm")
            verificationService.setEmail(userId, "user@example.com")

            // Send and verify
            verificationService.sendVerification(userId, ContactType.Email)
            val token = mockSender.sentTokens.first().second

            val firstResult = verificationService.verifyToken(userId, ContactType.Email, token)
            firstResult.shouldBeInstanceOf<VerificationResult.Success>()

            // Try to use the same token again - should fail
            val secondResult = verificationService.verifyToken(userId, ContactType.Email, token)
            secondResult.shouldBeInstanceOf<VerificationResult.Invalid>()
            (secondResult as VerificationResult.Invalid).reason shouldContain "used"
        }
    }
})
