@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.jdbc.greater
import com.mustafadakhel.kodex.lockout.schema.LockoutSchema
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LockoutIntegrationTest : FunSpec({

    lateinit var db: KodexDatabase
    lateinit var lockoutSchema: LockoutSchema
    lateinit var testSetup: TestDatabaseSetup

    beforeTest {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:lockout_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        lockoutSchema = LockoutSchema(core.prefix)
        db = KodexDatabase(
            dataSource = ds,
            dialect = DatabaseDialect.H2,
            core = core,
            extensionSchemas = mapOf(LockoutSchema::class to lockoutSchema)
        )
        db.createSchema()
        testSetup = TestDatabaseSetup(db)
    }

    context("Layer 1: Identifier Throttling") {

        test("should throttle identifier after max failed attempts") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
            val ipAddress = "192.168.1.1"

            repeat(3) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.Throttled>()

            val attempts = lockoutSchema.failedLoginAttempts
            db.transaction {
                val clockNow = CurrentKotlinInstant
                val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

                val count = select(attempts).where {
                    (attempts.identifier eq identifier) and (attempts.attemptedAt greater windowStart)
                }.count()
                count shouldBe 3
            }

            db.transaction {
                deleteFrom(attempts).where { attempts.identifier eq identifier }.execute()
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
                val uid = testSetup.createTestUser(email = "ipuser$index@test.com", realmId = "test-realm")
                service.recordFailedAttempt(
                    identifier = "user$index@example.com",
                    userId = uid,
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

            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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

            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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
                insertInto(locks) {
                    this[locks.realmId] = "test-realm"
                    this[locks.userId] = userId
                    this[locks.lockedUntil] = null
                    this[locks.reason] = "Permanent ban"
                    this[locks.lockedAt] = CurrentKotlinInstant.toLocalDateTime(TimeZone.UTC)
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
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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

            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
            val ipAddress = "192.168.1.1"

            repeat(100) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            service.shouldThrottleIdentifier(identifier).shouldBeInstanceOf<ThrottleResult.NotThrottled>()
            service.shouldThrottleIp(ipAddress).shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should respect disabled policy for lockout") {
            val policy = AccountLockoutPolicy.disabled()
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
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
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
            val ipAddress = "192.168.1.1"
            val reason = "Invalid password"

            service.recordFailedAttempt(identifier, userId, ipAddress, reason)

            val attempts = lockoutSchema.failedLoginAttempts
            db.transaction {
                val attempt = select(attempts).where {
                    attempts.identifier eq identifier
                }.firstOrNull { row ->
                    object {
                        val identifier = row[attempts.identifier]
                        val userId = row[attempts.userId]
                        val ipAddress = row[attempts.ipAddress]
                        val reason = row[attempts.reason]
                    }
                }!!

                attempt.identifier shouldBe identifier
                attempt.userId shouldBe userId
                attempt.ipAddress shouldBe ipAddress
                attempt.reason shouldBe reason
            }
        }

        test("should clean old attempts when recording new ones") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5, attemptWindow = 5.minutes, lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(db, lockoutSchema, policy, TimeZone.UTC, "test-realm")

            val identifier = "user@example.com"
            val userId = testSetup.createTestUser(email = "user-${System.nanoTime()}@test.com", realmId = "test-realm")
            val ipAddress = "192.168.1.1"

            repeat(2) { service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials") }

            val attempts = lockoutSchema.failedLoginAttempts
            db.transaction {
                val clockNow = CurrentKotlinInstant
                val oldTime = (clockNow - 10.minutes).toLocalDateTime(TimeZone.UTC)

                insertInto(attempts) {
                    this[attempts.realmId] = "test-realm"
                    this[attempts.identifier] = identifier
                    this[attempts.userId] = userId
                    this[attempts.ipAddress] = ipAddress
                    this[attempts.attemptedAt] = oldTime
                    this[attempts.reason] = "Old attempt"
                }
            }

            db.transaction {
                val count = select(attempts).where {
                    attempts.identifier eq identifier
                }.count()
                count shouldBe 3
            }

            service.recordFailedAttempt(identifier, userId, ipAddress, "New attempt")

            db.transaction {
                val count = select(attempts).where {
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
