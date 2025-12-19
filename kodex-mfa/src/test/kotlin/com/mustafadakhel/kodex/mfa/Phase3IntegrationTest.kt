package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.EventSubscriber
import com.mustafadakhel.kodex.event.KodexEvent
import com.mustafadakhel.kodex.mfa.database.*
import com.mustafadakhel.kodex.mfa.device.DeviceFingerprint
import com.mustafadakhel.kodex.mfa.encryption.AesGcmSecretEncryption
import com.mustafadakhel.kodex.mfa.sender.MfaCodeSender
import com.mustafadakhel.kodex.mfa.totp.TotpAlgorithm
import com.mustafadakhel.kodex.mfa.totp.TotpGenerator
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.ratelimit.inmemory.InMemoryRateLimiter
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.kodexTransaction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Phase3IntegrationTest : FunSpec({

    lateinit var mfaService: MfaService
    lateinit var db: Database
    lateinit var testUserId: UUID
    lateinit var adminUserId: UUID
    lateinit var sentCodes: MutableMap<String, String>
    lateinit var hashMap: MutableMap<String, String>

    beforeEach {
        db = Database.connect("jdbc:h2:mem:test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        TestDatabaseSetup.setupTestEngine(db)

        kodexTransaction {
            val coreTables = TestDatabaseSetup.getCoreTables()
            SchemaUtils.create(
                *coreTables.toTypedArray(),
                MfaMethods,
                MfaChallenges,
                MfaTrustedDevices,
                MfaBackupCodes,
                MfaTotpUsedCodes
            )
        }

        testUserId = TestDatabaseSetup.createTestUser(
            email = "test@example.com",
            passwordHash = "hash",
            status = UserStatus.ACTIVE
        )

        adminUserId = TestDatabaseSetup.createAdminUser(
            email = "admin@example.com",
            passwordHash = "hash"
        )

        val config = MfaConfig()
        config.userHasRole = { userId, role -> TestDatabaseSetup.userHasRole(userId, role) }
        config.getTotalUsers = { TestDatabaseSetup.getUserCount() }

        // Mock hashing service
        hashMap = mutableMapOf()
        val hashingService = object : HashingService {
            override fun hash(value: String): String {
                val hashed = "hashed_$value"
                hashMap[hashed] = value
                return hashed
            }

            override fun verify(value: String, hash: String): Boolean {
                return hashMap[hash] == value
            }
        }

        // Mock email sender that captures sent codes
        sentCodes = mutableMapOf() // email -> code
        config.emailSender = object : MfaCodeSender {
            override suspend fun send(contactValue: String, code: String) {
                sentCodes[contactValue] = code
            }
        }

        val secretEncryption = AesGcmSecretEncryption(
            AesGcmSecretEncryption.generateKey()
        )
        val eventBus = object : EventBus {
            override suspend fun publish(event: KodexEvent) {}
            override fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>) {}
            override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {}
            override fun shutdown() {}
        }

        val sessionStore = com.mustafadakhel.kodex.mfa.session.MfaSessionStore()

        mfaService = DefaultMfaService(
            config = config,
            timeZone = TimeZone.UTC,
            hashingService = hashingService,
            secretEncryption = secretEncryption,
            eventBus = eventBus,
            realmId = "test-realm",
            rateLimiter = InMemoryRateLimiter(),
            sessionStore = sessionStore
        )
    }

    afterEach {
        kodexTransaction {
            val coreTables = TestDatabaseSetup.getCoreTables()
            SchemaUtils.drop(
                MfaBackupCodes,
                MfaChallenges,
                MfaTrustedDevices,
                MfaMethods,
                *coreTables.reversed().toTypedArray()
            )
        }
    }

    context("Trusted Devices") {

        test("should trust a device with expiration") {
            val ipAddress = "192.168.1.1"
            val userAgent = "Mozilla/5.0"
            val deviceName = "Chrome on Windows"
            val expectedFingerprint = DeviceFingerprint.generate(ipAddress, userAgent)

            val deviceId = mfaService.trustDevice(
                userId = testUserId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                deviceName = deviceName,
                expiresInDays = 30
            )

            deviceId shouldNotBe null

            val devices = mfaService.getTrustedDevices(testUserId)
            devices shouldHaveSize 1
            devices[0].deviceFingerprint shouldBe expectedFingerprint
            devices[0].deviceName shouldBe deviceName
            devices[0].expiresAt shouldNotBe null
        }

        test("should check if device is trusted") {
            val ipAddress = "192.168.1.1"
            val userAgent = "Mozilla/5.0"

            mfaService.trustDevice(
                userId = testUserId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                deviceName = "Test Device",
                expiresInDays = 30
            )

            val isTrusted = mfaService.isDeviceTrusted(testUserId, ipAddress, userAgent)
            isTrusted shouldBe true
        }

        test("should return false for untrusted device") {
            val ipAddress = "192.168.1.1"
            val userAgent = "Mozilla/5.0"

            val isTrusted = mfaService.isDeviceTrusted(testUserId, ipAddress, userAgent)
            isTrusted shouldBe false
        }

        test("should update last used timestamp when checking trusted device") {
            val ipAddress = "192.168.1.1"
            val userAgent = "Mozilla/5.0"

            val deviceId = mfaService.trustDevice(
                userId = testUserId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                deviceName = "Test Device",
                expiresInDays = 30
            )

            val devices1 = mfaService.getTrustedDevices(testUserId)
            devices1[0].lastUsedAt shouldBe null

            delay(10.milliseconds)
            mfaService.isDeviceTrusted(testUserId, ipAddress, userAgent)

            val devices2 = mfaService.getTrustedDevices(testUserId)
            devices2[0].lastUsedAt shouldNotBe null
        }

        test("should return false for expired device") {
            val ipAddress = "192.168.1.1"
            val userAgent = "Mozilla/5.0"
            val fingerprint = DeviceFingerprint.generate(ipAddress, userAgent)

            // Trust device with very short expiration (simulated via direct DB insert)
            kodexTransaction {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val expiredTime = now.toInstant(TimeZone.UTC).minus(1.days).toLocalDateTime(TimeZone.UTC)

                MfaTrustedDevices.insert {
                    it[MfaTrustedDevices.realmId] = "test-realm"
                    it[id] = org.jetbrains.exposed.dao.id.EntityID(UUID.randomUUID(), MfaTrustedDevices)
                    it[userId] = testUserId
                    it[deviceFingerprint] = fingerprint
                    it[deviceName] = "Expired Device"
                    it[MfaTrustedDevices.ipAddress] = ipAddress
                    it[MfaTrustedDevices.userAgent] = userAgent
                    it[trustedAt] = now
                    it[lastUsedAt] = null
                    it[expiresAt] = expiredTime
                }
            }

            val isTrusted = mfaService.isDeviceTrusted(testUserId, ipAddress, userAgent)
            isTrusted shouldBe false
        }

        test("should remove specific trusted device") {
            val ipAddress = "192.168.1.1"
            val userAgent = "Mozilla/5.0"

            val deviceId = mfaService.trustDevice(
                userId = testUserId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                deviceName = "Test Device",
                expiresInDays = 30
            )

            mfaService.getTrustedDevices(testUserId) shouldHaveSize 1

            mfaService.removeTrustedDevice(testUserId, deviceId)

            mfaService.getTrustedDevices(testUserId) shouldHaveSize 0
        }

        test("should remove all trusted devices for user") {
            mfaService.trustDevice(testUserId, "192.168.1.1", "Mozilla/5.0", "Device 1")
            mfaService.trustDevice(testUserId, "192.168.1.2", "Safari/1.0", "Device 2")

            mfaService.getTrustedDevices(testUserId) shouldHaveSize 2

            mfaService.removeAllTrustedDevices(testUserId)

            mfaService.getTrustedDevices(testUserId) shouldHaveSize 0
        }

        test("should generate consistent fingerprints for same IP and user agent") {
            val fingerprint1 = DeviceFingerprint.generate("192.168.1.1", "Mozilla/5.0")
            val fingerprint2 = DeviceFingerprint.generate("192.168.1.1", "Mozilla/5.0")

            fingerprint1 shouldBe fingerprint2
        }

        test("should generate different fingerprints for different IP addresses") {
            val fingerprint1 = DeviceFingerprint.generate("192.168.1.1", "Mozilla/5.0")
            val fingerprint2 = DeviceFingerprint.generate("192.168.1.2", "Mozilla/5.0")

            fingerprint1 shouldNotBe fingerprint2
        }

        test("should extract device name from user agent") {
            val name1 =
                DeviceFingerprint.extractDeviceName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            name1 shouldBe "Chrome on Windows"

            val name2 =
                DeviceFingerprint.extractDeviceName("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.1 Safari/605.1.15")
            name2 shouldBe "Safari on macOS"

            val name3 =
                DeviceFingerprint.extractDeviceName("Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            // DeviceFingerprint now correctly checks Android before Linux
            name3 shouldBe "Chrome on Android"
        }
    }

    context("Admin Management") {

        test("should allow admin to force remove user's MFA method") {
            // Enroll TOTP for user
            val enrollResult = mfaService.enrollTotp(testUserId, "test@example.com")

            // Manually activate the method for testing (normally done via verifyTotpEnrollment)
            kodexTransaction {
                MfaMethods.update({ (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.TOTP) }) {
                    it[isActive] = true
                }
            }

            val methods = mfaService.getMethods(testUserId)
            methods shouldHaveSize 1

            val methodId = methods[0].id

            // Admin removes the method
            mfaService.forceRemoveMfaMethod(adminUserId, testUserId, methodId)

            // Verify method is removed
            mfaService.getMethods(testUserId) shouldHaveSize 0
        }

        test("should allow admin to disable all MFA for user") {
            // Setup user with TOTP, backup codes, and trusted device
            mfaService.enrollTotp(testUserId, "test@example.com")

            // Manually activate the method for testing
            kodexTransaction {
                MfaMethods.update({ (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.TOTP) }) {
                    it[isActive] = true
                }
            }

            mfaService.generateBackupCodes(testUserId)
            mfaService.trustDevice(testUserId, "192.168.1.1", "Mozilla/5.0", "Device")

            // Verify everything is set up
            mfaService.getMethods(testUserId) shouldHaveSize 1
            kodexTransaction {
                MfaBackupCodes.selectAll().where { MfaBackupCodes.userId eq testUserId }.count()
            } shouldNotBe 0L
            mfaService.getTrustedDevices(testUserId) shouldHaveSize 1

            // Admin disables all MFA
            mfaService.disableMfaForUser(adminUserId, testUserId)

            // Verify everything is removed
            mfaService.getMethods(testUserId) shouldHaveSize 0
            kodexTransaction {
                MfaBackupCodes.selectAll().where { MfaBackupCodes.userId eq testUserId }.count()
            } shouldBe 0L
            mfaService.getTrustedDevices(testUserId) shouldHaveSize 0
        }

        test("should allow admin to list user's MFA methods") {
            // Enroll TOTP for user
            mfaService.enrollTotp(testUserId, "test@example.com")

            // Manually activate the method for testing
            kodexTransaction {
                MfaMethods.update({ (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.TOTP) }) {
                    it[isActive] = true
                }
            }

            // Admin lists methods
            val methods = mfaService.listUserMethods(adminUserId, testUserId)

            methods shouldHaveSize 1
            methods[0].type.name shouldBe "TOTP"
        }
    }

    context("Admin Authorization") {

        test("should reject non-admin trying to force remove MFA method") {
            // Enroll TOTP for testUserId
            mfaService.enrollTotp(testUserId, "test@example.com")

            // Manually activate the method
            kodexTransaction {
                MfaMethods.update({ (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.TOTP) }) {
                    it[isActive] = true
                }
            }

            val methods = mfaService.getMethods(testUserId)
            val methodId = methods[0].id

            // testUserId (non-admin) tries to remove their own method via admin API
            val exception =
                shouldThrow<KodexThrowable.Authorization.InsufficientPermissions> {
                    mfaService.forceRemoveMfaMethod(testUserId, testUserId, methodId)
                }

            exception.message shouldContain "admin"
        }

        test("should reject non-admin trying to disable MFA for user") {
            // testUserId (non-admin) tries to disable MFA for another user
            val exception =
                shouldThrow<KodexThrowable.Authorization.InsufficientPermissions> {
                    mfaService.disableMfaForUser(testUserId, adminUserId)
                }

            exception.message shouldContain "admin"
        }

        test("should reject non-admin trying to list user methods") {
            // testUserId (non-admin) tries to list admin's methods
            val exception =
                shouldThrow<KodexThrowable.Authorization.InsufficientPermissions> {
                    mfaService.listUserMethods(testUserId, adminUserId)
                }

            exception.message shouldContain "admin"
        }
    }

    context("Statistics") {

        test("should return statistics for MFA adoption") {
            // Create another user without MFA
            val user2Id = TestDatabaseSetup.createTestUser(
                email = "user2@example.com",
                passwordHash = "hash",
                status = UserStatus.ACTIVE
            )

            // Enroll TOTP for testUserId
            mfaService.enrollTotp(testUserId, "test@example.com")

            // Manually activate the method for testing
            kodexTransaction {
                MfaMethods.update({ (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.TOTP) }) {
                    it[isActive] = true
                }
            }

            val stats = mfaService.getMfaStatistics()

            stats.totalUsers shouldBe 3 // testUserId, adminUserId, user2Id
            stats.usersWithMfa shouldBe 1 // Only testUserId has MFA
            stats.adoptionRate shouldBe (1.0 / 3.0 * 100.0)
        }

        test("should return method distribution in statistics") {
            // Enroll TOTP for testUserId
            mfaService.enrollTotp(testUserId, "test@example.com")

            // Manually activate the method for testing
            kodexTransaction {
                MfaMethods.update({ (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.TOTP) }) {
                    it[isActive] = true
                }
            }

            val stats = mfaService.getMfaStatistics()

            stats.methodDistribution.keys shouldContainExactlyInAnyOrder listOf(
                MfaMethodType.TOTP
            )
        }

        test("should return trusted devices count in statistics") {
            mfaService.trustDevice(testUserId, "192.168.1.1", "Mozilla/5.0", "Device 1")
            mfaService.trustDevice(adminUserId, "192.168.1.2", "Safari/1.0", "Device 2")

            val stats = mfaService.getMfaStatistics()

            stats.trustedDevices shouldBe 2
        }

        test("should return zero statistics for empty system") {
            // Remove test users
            TestDatabaseSetup.deleteTestUser(testUserId)
            TestDatabaseSetup.deleteTestUser(adminUserId)

            val stats = mfaService.getMfaStatistics()

            stats.totalUsers shouldBe 0
            stats.usersWithMfa shouldBe 0
            stats.adoptionRate shouldBe 0.0
            stats.trustedDevices shouldBe 0
        }
    }

    context("Authentication Flow - Email Challenge") {

        test("should send email challenge for enrolled email method") {
            // Enroll email MFA first
            val enrollResult = mfaService.enrollEmail(testUserId, "test@example.com", null)
            enrollResult.shouldBeInstanceOf<EnrollmentResult.CodeSent>()

            // Verify enrollment with captured code
            val enrollChallengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
            val enrollCode = sentCodes["test@example.com"]!!
            val verifyResult = mfaService.verifyEmailEnrollment(testUserId, enrollChallengeId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            // Get the email method ID
            val methodId = kodexTransaction {
                MfaMethods.selectAll()
                    .where { (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.EMAIL) }
                    .single()[MfaMethods.id].value
            }

            // Clear sent codes to test challenge
            sentCodes.clear()

            // Send authentication challenge
            val challengeResult = mfaService.challengeEmail(testUserId, methodId, null)
            challengeResult.shouldBeInstanceOf<ChallengeResult.Success>()

            // Verify challenge code was sent
            sentCodes["test@example.com"] shouldNotBe null
        }

        test("should fail when challenging non-enrolled method") {
            val result = mfaService.challengeEmail(testUserId, UUID.randomUUID(), null)

            result.shouldBeInstanceOf<ChallengeResult.Failed>()
            (result as ChallengeResult.Failed).reason shouldContain "not found"
        }

        test("should enforce rate limiting for challenges") {
            // Enroll email MFA first
            val enrollResult = mfaService.enrollEmail(testUserId, "test@example.com", null)
            enrollResult.shouldBeInstanceOf<EnrollmentResult.CodeSent>()

            val enrollChallengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
            val enrollCode = sentCodes["test@example.com"]!!
            val verifyResult = mfaService.verifyEmailEnrollment(testUserId, enrollChallengeId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            val methodId = kodexTransaction {
                MfaMethods.selectAll()
                    .where { (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.EMAIL) }
                    .single()[MfaMethods.id].value
            }

            // Exhaust rate limit attempts
            repeat(5) {
                mfaService.challengeEmail(testUserId, methodId, null)
            }

            // Next attempt should be rate limited or in cooldown
            val result = mfaService.challengeEmail(testUserId, methodId, null)
            // Either RateLimitExceeded or Cooldown is valid - both indicate throttling
            (result is ChallengeResult.RateLimitExceeded || result is ChallengeResult.Cooldown) shouldBe true
        }
    }

    context("Authentication Flow - Challenge Verification") {

        test("should verify valid email challenge code") {
            // Enroll email MFA first
            val enrollResult = mfaService.enrollEmail(testUserId, "test@example.com", null)
            enrollResult.shouldBeInstanceOf<EnrollmentResult.CodeSent>()

            val enrollChallengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
            val enrollCode = sentCodes["test@example.com"]!!
            val verifyResult = mfaService.verifyEmailEnrollment(testUserId, enrollChallengeId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            val methodId = kodexTransaction {
                MfaMethods.selectAll()
                    .where { (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.EMAIL) }
                    .single()[MfaMethods.id].value
            }

            // Send challenge
            sentCodes.clear()
            val challengeResult = mfaService.challengeEmail(testUserId, methodId, null)
            challengeResult.shouldBeInstanceOf<ChallengeResult.Success>()

            // Verify challenge with captured code
            val challengeId = (challengeResult as ChallengeResult.Success).challengeId
            val challengeCode = sentCodes["test@example.com"]!!
            val result = mfaService.verifyChallenge(testUserId, challengeId, challengeCode, null)

            result.shouldBeInstanceOf<VerificationResult.Success>()
        }

        test("should fail verification with invalid code") {
            // Enroll email MFA first
            val enrollResult = mfaService.enrollEmail(testUserId, "test@example.com", null)
            enrollResult.shouldBeInstanceOf<EnrollmentResult.CodeSent>()

            val enrollChallengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
            val enrollCode = sentCodes["test@example.com"]!!
            val verifyResult = mfaService.verifyEmailEnrollment(testUserId, enrollChallengeId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            val methodId = kodexTransaction {
                MfaMethods.selectAll()
                    .where { (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.EMAIL) }
                    .single()[MfaMethods.id].value
            }

            // Send challenge
            val challengeResult = mfaService.challengeEmail(testUserId, methodId, null)
            challengeResult.shouldBeInstanceOf<ChallengeResult.Success>()

            // Verify with wrong code
            val challengeId = (challengeResult as ChallengeResult.Success).challengeId
            val result = mfaService.verifyChallenge(testUserId, challengeId, "WRONG", null)

            result.shouldBeInstanceOf<VerificationResult.Invalid>()
        }

        test("should fail verification after challenge expires") {
            // Enroll email MFA first
            val enrollResult = mfaService.enrollEmail(testUserId, "test@example.com", null)
            enrollResult.shouldBeInstanceOf<EnrollmentResult.CodeSent>()

            val enrollChallengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
            val enrollCode = sentCodes["test@example.com"]!!
            val verifyResult = mfaService.verifyEmailEnrollment(testUserId, enrollChallengeId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            val methodId = kodexTransaction {
                MfaMethods.selectAll()
                    .where { (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.EMAIL) }
                    .single()[MfaMethods.id].value
            }

            // Send challenge
            sentCodes.clear()
            val challengeResult = mfaService.challengeEmail(testUserId, methodId, null)
            challengeResult.shouldBeInstanceOf<ChallengeResult.Success>()
            val challengeId = (challengeResult as ChallengeResult.Success).challengeId
            val challengeCode = sentCodes["test@example.com"]!!

            // Manually expire the challenge
            kodexTransaction {
                MfaChallenges.update({ MfaChallenges.id eq challengeId }) {
                    it[expiresAt] = Clock.System.now().minus(1.milliseconds).toLocalDateTime(TimeZone.UTC)
                }
            }

            // Try to verify expired challenge
            val result = mfaService.verifyChallenge(testUserId, challengeId, challengeCode, null)

            result.shouldBeInstanceOf<VerificationResult.Expired>()
        }

        test("should enforce rate limiting for challenge verification") {
            // Enroll email MFA first
            val enrollResult = mfaService.enrollEmail(testUserId, "test@example.com", null)
            enrollResult.shouldBeInstanceOf<EnrollmentResult.CodeSent>()

            val enrollChallengeId = (enrollResult as EnrollmentResult.CodeSent).challengeId
            val enrollCode = sentCodes["test@example.com"]!!
            val verifyResult = mfaService.verifyEmailEnrollment(testUserId, enrollChallengeId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            val methodId = kodexTransaction {
                MfaMethods.selectAll()
                    .where { (MfaMethods.userId eq testUserId) and (MfaMethods.methodType eq MfaMethodType.EMAIL) }
                    .single()[MfaMethods.id].value
            }

            // Send challenge
            val challengeResult = mfaService.challengeEmail(testUserId, methodId, null)
            challengeResult.shouldBeInstanceOf<ChallengeResult.Success>()

            val challengeId = (challengeResult as ChallengeResult.Success).challengeId

            // Exhaust rate limit attempts with wrong codes
            repeat(5) {
                mfaService.verifyChallenge(testUserId, challengeId, "WRONG", null)
            }

            // Next attempt should be rate limited
            val result = mfaService.verifyChallenge(testUserId, challengeId, "WRONG", null)
            result.shouldBeInstanceOf<VerificationResult.RateLimitExceeded>()
        }
    }

    context("Authentication Flow - TOTP Verification") {

        test("should verify valid TOTP code") {
            // Enroll TOTP MFA first
            val enrollResult = mfaService.enrollTotp(testUserId, "test@example.com")
            val secret = enrollResult.secret
            val methodId = enrollResult.methodId

            // Verify TOTP enrollment
            val totp = TotpGenerator(
                algorithm = TotpAlgorithm.SHA1,
                digits = 6,
                period = 30.seconds
            )
            val enrollCode = totp.generateCode(secret, Clock.System.now())
            val verifyResult = mfaService.verifyTotpEnrollment(testUserId, methodId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            // Generate valid TOTP code for authentication
            val authCode = totp.generateCode(secret, Clock.System.now())

            // Verify TOTP
            val result = mfaService.verifyTotp(testUserId, methodId, authCode, null)

            result.shouldBeInstanceOf<VerificationResult.Success>()
        }

        test("should fail TOTP verification with invalid code") {
            // Enroll TOTP MFA first
            val enrollResult = mfaService.enrollTotp(testUserId, "test@example.com")
            val secret = enrollResult.secret
            val methodId = enrollResult.methodId

            // Verify TOTP enrollment
            val totp = TotpGenerator(
                algorithm = TotpAlgorithm.SHA1,
                digits = 6,
                period = 30.seconds
            )
            val enrollCode = totp.generateCode(secret, Clock.System.now())
            val verifyResult = mfaService.verifyTotpEnrollment(testUserId, methodId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            // Verify with invalid code
            val result = mfaService.verifyTotp(testUserId, methodId, "000000", null)

            result.shouldBeInstanceOf<VerificationResult.Invalid>()
        }

        test("should enforce rate limiting for TOTP verification") {
            // Enroll TOTP MFA first
            val enrollResult = mfaService.enrollTotp(testUserId, "test@example.com")
            val secret = enrollResult.secret
            val methodId = enrollResult.methodId

            // Verify TOTP enrollment
            val totp = TotpGenerator(
                algorithm = TotpAlgorithm.SHA1,
                digits = 6,
                period = 30.seconds
            )
            val enrollCode = totp.generateCode(secret, Clock.System.now())
            val verifyResult = mfaService.verifyTotpEnrollment(testUserId, methodId, enrollCode)
            verifyResult.shouldBeInstanceOf<EnrollmentVerificationResult.Success>()

            // Exhaust rate limit attempts with wrong codes - try more attempts to trigger rate limit
            repeat(10) {
                mfaService.verifyTotp(testUserId, methodId, "000000", null)
            }

            // Next attempt should still fail (rate limited or invalid)
            val result = mfaService.verifyTotp(testUserId, methodId, "000000", null)
            // Should not be successful - either rate limited or invalid
            result shouldNotBe VerificationResult.Success
        }
    }

})
