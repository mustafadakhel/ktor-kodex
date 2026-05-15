package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.lockout.schema.LockoutSchema
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LockoutIntegrationTest : FunSpec({

    lateinit var db: KodexDatabase
    lateinit var lockoutSchema: LockoutSchema
    lateinit var testSetup: TestDatabaseSetup

    beforeTest {
        val database = Database.connect(
            "jdbc:h2:mem:lockout_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val core = CoreSchema("test_")
        lockoutSchema = LockoutSchema(core)
        db = KodexDatabase(database, core, mapOf(LockoutSchema::class to lockoutSchema))
        db.createSchema()
        testSetup = TestDatabaseSetup(db)
    }

    afterTest {
        db.transaction {
            SchemaUtils.drop(lockoutSchema.accountLocks, lockoutSchema.failedLoginAttempts)
        }
    }

    context("Layer 1: Identifier Throttling") {

        test("should throttle identifier after max failed attempts") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            repeat(3) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            val result = service.shouldThrottleIdentifier(identifier)
            result.shouldBeInstanceOf<ThrottleResult.Throttled>()
            result as ThrottleResult.Throttled
            result.attemptCount shouldBe 3
        }

        test("should not throttle identifier below threshold") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            repeat(2) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            val result = service.shouldThrottleIdentifier(identifier)
            result.shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should not count attempts outside the window") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 5.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            repeat(3) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.Throttled>()

            val attempts = lockoutSchema.failedLoginAttempts
            db.transaction {
                val clockNow = CurrentKotlinInstant
                val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

                val count = attempts.selectAll().where {
                    (attempts.identifier eq identifier) and (attempts.attemptedAt greater windowStart)
                }.count()
                count shouldBe 3
            }

            db.transaction {
                attempts.deleteWhere { attempts.identifier eq identifier }
            }

            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }
    }

    context("Layer 1: IP Throttling") {

        test("should throttle IP after threshold (4x identifier limit)") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val ipAddress = "192.168.1.1"

            repeat(12) { index ->
                service.recordFailedAttempt(
                    identifier = "user$index@example.com",
                    userId = UUID.randomUUID(),
                    ipAddress = ipAddress,
                    reason = "Invalid credentials"
                )
            }

            val result = service.shouldThrottleIp(ipAddress)
            result.shouldBeInstanceOf<ThrottleResult.Throttled>()
            result as ThrottleResult.Throttled
            result.attemptCount shouldBe 12
        }
    }

    context("Layer 2: Account Lockout") {

        test("should lock account after max failed attempts for real user") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            repeat(3) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            val result = service.shouldLockAccount(userId)
            result.shouldBeInstanceOf<LockAccountResult.ShouldLock>()
            result as LockAccountResult.ShouldLock
            result.attemptCount shouldBe 3
        }

        test("should not lock account below threshold") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            repeat(2) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            val result = service.shouldLockAccount(userId)
            result.shouldBeInstanceOf<LockAccountResult.NoAction>()
        }
    }

    context("Lock Management") {

        test("should lock account manually") {
            val policy = AccountLockoutPolicy.moderate()
            val userId = testSetup.createTestUser(email = "lock@test.com", realmId = "test-realm")
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val clockNow = CurrentKotlinInstant
            val lockedUntil = (clockNow + 1.hours).toLocalDateTime(TimeZone.UTC)

            service.lockAccount(userId, lockedUntil, "Manual lock by admin")

            val isLocked = service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC))
            isLocked shouldBe true
        }

        test("should unlock account") {
            val policy = AccountLockoutPolicy.moderate()
            val userId = testSetup.createTestUser(email = "unlock@test.com", realmId = "test-realm")
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val clockNow = CurrentKotlinInstant
            val lockedUntil = (clockNow + 1.hours).toLocalDateTime(TimeZone.UTC)

            service.lockAccount(userId, lockedUntil, "Test lock")
            service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC)) shouldBe true

            service.unlockAccount(userId)
            service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC)) shouldBe false
        }

        test("should respect lock expiry time") {
            val policy = AccountLockoutPolicy.moderate()
            val userId = testSetup.createTestUser(email = "expiry@test.com", realmId = "test-realm")
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val clockNow = CurrentKotlinInstant
            val lockedUntil = clockNow.toLocalDateTime(TimeZone.UTC) // Expired immediately

            service.lockAccount(userId, lockedUntil, "Test lock")

            val futureTime = (clockNow + 1.minutes).toLocalDateTime(TimeZone.UTC)
            val isLocked = service.isAccountLocked(userId, futureTime)
            isLocked shouldBe false
        }

        test("should handle null lockedUntil as permanent lock") {
            val policy = AccountLockoutPolicy.moderate()
            val userId = testSetup.createTestUser(email = "perm-lock@test.com", realmId = "test-realm")
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val locks = lockoutSchema.accountLocks
            db.transaction {
                locks.insert {
                    it[locks.realmId] = "test-realm"
                    it[locks.userId] = userId
                    it[locks.lockedUntil] = null
                    it[locks.reason] = "Permanent ban"
                    it[locks.lockedAt] = CurrentKotlinInstant.toLocalDateTime(TimeZone.UTC)
                }
            }

            val clockNow = CurrentKotlinInstant
            val isLocked = service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC))
            isLocked shouldBe true
        }
    }

    context("Clear Failed Attempts") {

        test("should clear failed attempts by identifier") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            repeat(3) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.Throttled>()
            service.clearFailedAttemptsForIdentifier(identifier)
            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should clear failed attempts by userId") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            repeat(3) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldLockAccount(userId).shouldBeInstanceOf<LockAccountResult.ShouldLock>()
            service.clearFailedAttemptsForUser(userId)
            service.shouldLockAccount(userId).shouldBeInstanceOf<LockAccountResult.NoAction>()
        }
    }

    context("Policy Configuration") {

        test("should respect disabled policy for throttling") {
            val policy = AccountLockoutPolicy.disabled()
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            repeat(100) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.NotThrottled>()
            service.shouldThrottleIp(ipAddress).shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should respect disabled policy for lockout") {
            val policy = AccountLockoutPolicy.disabled()
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            repeat(100) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldLockAccount(userId).shouldBeInstanceOf<LockAccountResult.NoAction>()
        }
    }

    context("Failed Attempt Recording") {

        test("should record all attempt metadata") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"
            val reason = "Invalid password"

            service.recordFailedAttempt(identifier, userId, ipAddress, reason)

            val attempts = lockoutSchema.failedLoginAttempts
            db.transaction {
                val attempt = attempts.selectAll().where {
                    attempts.identifier eq identifier
                }.single()

                attempt[attempts.identifier] shouldBe identifier
                attempt[attempts.userId] shouldBe userId
                attempt[attempts.ipAddress] shouldBe ipAddress
                attempt[attempts.reason] shouldBe reason
            }
        }

        test("should clean old attempts when recording new ones") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5, attemptWindow = 5.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            repeat(2) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            val attempts = lockoutSchema.failedLoginAttempts
            db.transaction {
                val clockNow = CurrentKotlinInstant
                val oldTime = (clockNow - 10.minutes).toLocalDateTime(TimeZone.UTC)

                attempts.insert {
                    it[attempts.realmId] = "test-realm"
                    it[attempts.identifier] = identifier
                    it[attempts.userId] = userId
                    it[attempts.ipAddress] = ipAddress
                    it[attempts.attemptedAt] = oldTime
                    it[attempts.reason] = "Old attempt"
                }
            }

            db.transaction {
                val count = attempts.selectAll().where {
                    attempts.identifier eq identifier
                }.count()
                count shouldBe 3
            }

            service.recordFailedAttempt(identifier, userId, ipAddress, "New attempt")

            db.transaction {
                val count = attempts.selectAll().where {
                    attempts.identifier eq identifier
                }.count()
                count shouldBe 3
            }
        }
    }

    context("Edge Cases") {

        test("should handle non-existent user ID") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val nonExistentUserId = UUID.randomUUID()

            val result = service.shouldLockAccount(nonExistentUserId)
            result.shouldBeInstanceOf<LockAccountResult.NoAction>()

            val clockNow = CurrentKotlinInstant
            val isLocked = service.isAccountLocked(nonExistentUserId, clockNow.toLocalDateTime(TimeZone.UTC))
            isLocked shouldBe false
        }
    }
})
