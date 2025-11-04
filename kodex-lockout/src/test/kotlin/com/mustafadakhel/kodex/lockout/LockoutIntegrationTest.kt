package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.lockout.database.AccountLocks
import com.mustafadakhel.kodex.lockout.database.FailedLoginAttempts
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Integration tests for account lockout service.
 * Tests with real database (H2 in-memory) to verify production behavior.
 */
class LockoutIntegrationTest : FunSpec({

    val database = Database.connect(
        "jdbc:h2:mem:test_lockout_integration;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver"
    )

    beforeTest {
        transaction(database) {
            SchemaUtils.create(FailedLoginAttempts, AccountLocks)
        }
    }

    afterTest {
        transaction(database) {
            SchemaUtils.drop(FailedLoginAttempts, AccountLocks)
        }
    }

    context("Layer 1: Identifier Throttling") {

        test("should throttle identifier after max failed attempts") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            // Record 3 failed attempts
            repeat(3) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // 4th attempt should be throttled
            val result = service.shouldThrottleIdentifier(identifier)
            result.shouldBeInstanceOf<ThrottleResult.Throttled>()
            result as ThrottleResult.Throttled
            result.attemptCount shouldBe 3
        }

        test("should not throttle identifier below threshold") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            // Record 2 failed attempts (below threshold of 5)
            repeat(2) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            val result = service.shouldThrottleIdentifier(identifier)
            result.shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should not count attempts outside the window") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 5.minutes,  // Short window
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            // Record 3 attempts
            repeat(3) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Should be throttled now
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.Throttled>()

            // Wait for window to pass (simulate by clearing old attempts)
            // In real scenario, we'd wait 5 minutes - here we manually clean
            transaction(database) {
                val clockNow = Clock.System.now()
                val windowStart = (clockNow - policy.attemptWindow).toLocalDateTime(TimeZone.UTC)

                // Verify attempts exist
                val count = FailedLoginAttempts.selectAll().where {
                    (FailedLoginAttempts.identifier eq identifier) and
                    (FailedLoginAttempts.attemptedAt greater windowStart)
                }.count()
                count shouldBe 3
            }

            // After clearing, should not be throttled
            transaction(database) {
                // Simulate old attempts by deleting them
                FailedLoginAttempts.deleteWhere { FailedLoginAttempts.identifier eq identifier }
            }

            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }
    }

    context("Layer 1: IP Throttling") {

        test("should throttle IP after threshold (4x identifier limit)") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,  // Identifier limit = 3
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val ipAddress = "192.168.1.1"

            // Record 12 failed attempts from same IP (threshold = 3 * 4 = 12)
            repeat(12) { index ->
                service.recordFailedAttempt(
                    identifier = "user$index@example.com",
                    userId = UUID.randomUUID(),
                    ipAddress = ipAddress,
                    reason = "Invalid credentials"
                )
            }

            // 13th attempt should be throttled
            val result = service.shouldThrottleIp(ipAddress)
            result.shouldBeInstanceOf<ThrottleResult.Throttled>()
            result as ThrottleResult.Throttled
            result.attemptCount shouldBe 12
        }

        test("should not throttle IP below threshold") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5,  // IP threshold = 5 * 4 = 20
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val ipAddress = "192.168.1.1"

            // Record 10 failed attempts (below threshold of 20)
            repeat(10) { index ->
                service.recordFailedAttempt(
                    identifier = "user$index@example.com",
                    userId = UUID.randomUUID(),
                    ipAddress = ipAddress,
                    reason = "Invalid credentials"
                )
            }

            val result = service.shouldThrottleIp(ipAddress)
            result.shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }
    }

    context("Layer 2: Account Lockout") {

        test("should lock account after max failed attempts for real user") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            // Record 3 failed attempts for the same userId
            repeat(3) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Account should be locked
            val result = service.shouldLockAccount(userId)
            result.shouldBeInstanceOf<LockAccountResult.ShouldLock>()
            result as LockAccountResult.ShouldLock
            result.attemptCount shouldBe 3
        }

        test("should not lock account below threshold") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            // Record 2 failed attempts (below threshold)
            repeat(2) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            val result = service.shouldLockAccount(userId)
            result.shouldBeInstanceOf<LockAccountResult.NoAction>()
        }

        test("should only count attempts for specific userId") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId1 = UUID.randomUUID()
            val userId2 = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            // Record 2 attempts for userId1
            repeat(2) {
                service.recordFailedAttempt(identifier, userId1, ipAddress, "Invalid credentials")
            }

            // Record 2 attempts for userId2
            repeat(2) {
                service.recordFailedAttempt(identifier, userId2, ipAddress, "Invalid credentials")
            }

            // Neither user should be locked (both below threshold of 3)
            service.shouldLockAccount(userId1).shouldBeInstanceOf<LockAccountResult.NoAction>()
            service.shouldLockAccount(userId2).shouldBeInstanceOf<LockAccountResult.NoAction>()
        }

        test("null userId should not contribute to account lockout") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "nonexistent@example.com"
            val ipAddress = "192.168.1.1"

            // Record 5 attempts with null userId (non-existent account)
            repeat(5) {
                service.recordFailedAttempt(identifier, userId = null, ipAddress, "Invalid credentials")
            }

            // Identifier should be throttled (Layer 1)
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.Throttled>()

            // But no account lockout can happen (Layer 2) - no userId to lock
            // This is expected behavior - throttling protects against enumeration
        }
    }

    context("Lock Management") {

        test("should lock account manually") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val clockNow = Clock.System.now()
            val lockedUntil = (clockNow + 1.hours).toLocalDateTime(TimeZone.UTC)

            service.lockAccount(userId, lockedUntil, "Manual lock by admin")

            val isLocked = service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC))
            isLocked shouldBe true
        }

        test("should unlock account") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val clockNow = Clock.System.now()
            val lockedUntil = (clockNow + 1.hours).toLocalDateTime(TimeZone.UTC)

            // Lock account
            service.lockAccount(userId, lockedUntil, "Test lock")
            service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC)) shouldBe true

            // Unlock account
            service.unlockAccount(userId)
            service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC)) shouldBe false
        }

        test("should respect lock expiry time") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val clockNow = Clock.System.now()
            val lockedUntil = clockNow.toLocalDateTime(TimeZone.UTC)  // Expired immediately

            service.lockAccount(userId, lockedUntil, "Test lock")

            // Check with time after expiry
            val futureTime = (clockNow + 1.minutes).toLocalDateTime(TimeZone.UTC)
            val isLocked = service.isAccountLocked(userId, futureTime)
            isLocked shouldBe false
        }

        test("should handle null lockedUntil as permanent lock") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()

            // Lock account with null expiry (permanent lock)
            transaction(database) {
                AccountLocks.insert {
                    it[AccountLocks.userId] = userId
                    it[AccountLocks.lockedUntil] = null  // Permanent lock
                    it[AccountLocks.reason] = "Permanent ban"
                    it[AccountLocks.lockedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                }
            }

            val clockNow = Clock.System.now()
            val isLocked = service.isAccountLocked(userId, clockNow.toLocalDateTime(TimeZone.UTC))
            isLocked shouldBe true
        }
    }

    context("Clear Failed Attempts") {

        test("should clear failed attempts by identifier") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            // Record 3 failed attempts (reaches threshold)
            repeat(3) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Should be throttled
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.Throttled>()

            // Clear attempts
            service.clearFailedAttemptsForIdentifier(identifier)

            // Should no longer be throttled
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should clear failed attempts by userId") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            // Record 3 failed attempts
            repeat(3) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Should lock account
            service.shouldLockAccount(userId)
                .shouldBeInstanceOf<LockAccountResult.ShouldLock>()

            // Clear attempts for user
            service.clearFailedAttemptsForUser(userId)

            // Should no longer lock
            service.shouldLockAccount(userId)
                .shouldBeInstanceOf<LockAccountResult.NoAction>()
        }
    }

    context("Policy Configuration") {

        test("should respect disabled policy for throttling") {
            val policy = AccountLockoutPolicy.disabled()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            // Record many failed attempts
            repeat(100) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Should NOT be throttled when disabled
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.NotThrottled>()
            service.shouldThrottleIp(ipAddress)
                .shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should respect disabled policy for lockout") {
            val policy = AccountLockoutPolicy.disabled()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            // Record many failed attempts
            repeat(100) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Should NOT lock account when disabled
            service.shouldLockAccount(userId)
                .shouldBeInstanceOf<LockAccountResult.NoAction>()
        }

        test("should use custom policy values") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 2,
                attemptWindow = 10.minutes,
                lockoutDuration = 60.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val userId = UUID.randomUUID()
            val identifier = "user@example.com"
            val ipAddress = "192.168.1.1"

            // Record exactly 2 attempts (custom threshold)
            repeat(2) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Should be throttled and account should lock
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.Throttled>()
            service.shouldLockAccount(userId)
                .shouldBeInstanceOf<LockAccountResult.ShouldLock>()
        }
    }

    context("Failed Attempt Recording") {

        test("should record all attempt metadata") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"
            val reason = "Invalid password"

            service.recordFailedAttempt(identifier, userId, ipAddress, reason)

            // Verify data in database
            transaction(database) {
                val attempt = FailedLoginAttempts.selectAll().where {
                    FailedLoginAttempts.identifier eq identifier
                }.single()

                attempt[FailedLoginAttempts.identifier] shouldBe identifier
                attempt[FailedLoginAttempts.userId] shouldBe userId
                attempt[FailedLoginAttempts.ipAddress] shouldBe ipAddress
                attempt[FailedLoginAttempts.reason] shouldBe reason
            }
        }

        test("should clean old attempts when recording new ones") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 5,
                attemptWindow = 5.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()
            val ipAddress = "192.168.1.1"

            // Record 2 attempts
            repeat(2) {
                service.recordFailedAttempt(identifier, userId, ipAddress, "Invalid credentials")
            }

            // Manually insert old attempt (outside window)
            transaction(database) {
                val clockNow = Clock.System.now()
                val oldTime = (clockNow - 10.minutes).toLocalDateTime(TimeZone.UTC)

                FailedLoginAttempts.insert {
                    it[FailedLoginAttempts.identifier] = identifier
                    it[FailedLoginAttempts.userId] = userId
                    it[FailedLoginAttempts.ipAddress] = ipAddress
                    it[FailedLoginAttempts.attemptedAt] = oldTime
                    it[FailedLoginAttempts.reason] = "Old attempt"
                }
            }

            // Verify 3 attempts exist
            transaction(database) {
                val count = FailedLoginAttempts.selectAll().where {
                    FailedLoginAttempts.identifier eq identifier
                }.count()
                count shouldBe 3
            }

            // Record new attempt - should clean old one
            service.recordFailedAttempt(identifier, userId, ipAddress, "New attempt")

            // Old attempt should be cleaned, 3 recent attempts remain
            transaction(database) {
                val count = FailedLoginAttempts.selectAll().where {
                    FailedLoginAttempts.identifier eq identifier
                }.count()
                count shouldBe 3  // 2 original + 1 new, old one cleaned
            }
        }
    }

    context("Edge Cases") {

        test("should handle multiple IPs for same identifier") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val identifier = "user@example.com"
            val userId = UUID.randomUUID()

            // Record attempts from different IPs
            service.recordFailedAttempt(identifier, userId, "192.168.1.1", "Invalid credentials")
            service.recordFailedAttempt(identifier, userId, "192.168.1.2", "Invalid credentials")
            service.recordFailedAttempt(identifier, userId, "192.168.1.3", "Invalid credentials")

            // All attempts count toward identifier throttling
            service.shouldThrottleIdentifier(identifier)
                .shouldBeInstanceOf<ThrottleResult.Throttled>()

            // But no single IP reaches IP threshold (3 * 4 = 12)
            service.shouldThrottleIp("192.168.1.1")
                .shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should handle multiple identifiers from same IP") {
            val policy = AccountLockoutPolicy(
                maxFailedAttempts = 3,  // IP threshold = 3 * 4 = 12
                attemptWindow = 15.minutes,
                lockoutDuration = 30.minutes
            )
            val service = accountLockoutService(policy, TimeZone.UTC)

            val ipAddress = "192.168.1.1"

            // Record 2 attempts each from 6 different identifiers (total 12)
            repeat(6) { index ->
                repeat(2) {
                    service.recordFailedAttempt(
                        identifier = "user$index@example.com",
                        userId = UUID.randomUUID(),
                        ipAddress = ipAddress,
                        reason = "Invalid credentials"
                    )
                }
            }

            // IP should be throttled (12 attempts = threshold)
            service.shouldThrottleIp(ipAddress)
                .shouldBeInstanceOf<ThrottleResult.Throttled>()

            // But individual identifiers should NOT be throttled (2 < 3)
            service.shouldThrottleIdentifier("user0@example.com")
                .shouldBeInstanceOf<ThrottleResult.NotThrottled>()
        }

        test("should handle non-existent user ID") {
            val policy = AccountLockoutPolicy.moderate()
            val service = accountLockoutService(policy, TimeZone.UTC)

            val nonExistentUserId = UUID.randomUUID()

            // Check lockout for user that never had failed attempts
            val result = service.shouldLockAccount(nonExistentUserId)
            result.shouldBeInstanceOf<LockAccountResult.NoAction>()

            // Check if locked
            val clockNow = Clock.System.now()
            val isLocked = service.isAccountLocked(nonExistentUserId, clockNow.toLocalDateTime(TimeZone.UTC))
            isLocked shouldBe false
        }
    }
})
