package com.mustafadakhel.kodex.security

import com.mustafadakhel.kodex.model.database.AccountLockouts
import com.mustafadakhel.kodex.model.database.FailedLoginAttempts
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.deleteAll
import kotlin.time.Duration.Companion.minutes

class AccountLockoutServiceTest : StringSpec({

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:test_lockout;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = false)
    }

    afterEach {
        exposedTransaction {
            FailedLoginAttempts.deleteAll()
            AccountLockouts.deleteAll()
        }
        Db.clearEngine()
    }

    "should not lock account when below threshold" {
        val policy = AccountLockoutPolicy.moderate() // 5 attempts
        val service = accountLockoutService(policy)

        repeat(4) {
            service.recordFailedAttempt(
                identifier = "test@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        val result = service.checkLockout("test@example.com", TimeZone.UTC)
        result.shouldBe(LockoutResult.NotLocked)
    }

    "should lock account after exceeding threshold" {
        val policy = AccountLockoutPolicy(
            maxFailedAttempts = 3,
            attemptWindow = 15.minutes,
            lockoutDuration = 30.minutes
        )
        val service = accountLockoutService(policy)

        repeat(3) {
            service.recordFailedAttempt(
                identifier = "test@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        val result = service.checkLockout("test@example.com", TimeZone.UTC)
        result.shouldBeInstanceOf<LockoutResult.Locked>()
    }

    "should unlock account when lockout expires" {
        val policy = AccountLockoutPolicy(
            maxFailedAttempts = 2,
            attemptWindow = 15.minutes,
            lockoutDuration = 1.minutes
        )
        val service = accountLockoutService(policy)

        repeat(2) {
            service.recordFailedAttempt(
                identifier = "test@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        val result = service.checkLockout("test@example.com", TimeZone.UTC)
        result.shouldBeInstanceOf<LockoutResult.Locked>()
    }

    "should clear failed attempts" {
        val policy = AccountLockoutPolicy.moderate()
        val service = accountLockoutService(policy)

        repeat(3) {
            service.recordFailedAttempt(
                identifier = "test@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        service.clearFailedAttempts("test@example.com")

        val result = service.checkLockout("test@example.com", TimeZone.UTC)
        result.shouldBe(LockoutResult.NotLocked)
    }

    "should unlock account manually" {
        val policy = AccountLockoutPolicy.strict()
        val service = accountLockoutService(policy)

        repeat(3) {
            service.recordFailedAttempt(
                identifier = "test@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        service.unlockAccount("test@example.com")

        val result = service.checkLockout("test@example.com", TimeZone.UTC)
        result.shouldBe(LockoutResult.NotLocked)
    }

    "should not lock when policy is disabled" {
        val policy = AccountLockoutPolicy.disabled()
        val service = accountLockoutService(policy)

        repeat(100) {
            service.recordFailedAttempt(
                identifier = "test@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        val result = service.checkLockout("test@example.com", TimeZone.UTC)
        result.shouldBe(LockoutResult.NotLocked)
    }

    "should track attempts per identifier separately" {
        val policy = AccountLockoutPolicy(maxFailedAttempts = 3, attemptWindow = 15.minutes, lockoutDuration = 30.minutes)
        val service = accountLockoutService(policy)

        repeat(3) {
            service.recordFailedAttempt(
                identifier = "user1@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        repeat(2) {
            service.recordFailedAttempt(
                identifier = "user2@example.com",
                ipAddress = "127.0.0.1",
                userAgent = "Test Agent",
                reason = "Invalid password"
            )
        }

        val result1 = service.checkLockout("user1@example.com", TimeZone.UTC)
        val result2 = service.checkLockout("user2@example.com", TimeZone.UTC)

        result1.shouldBeInstanceOf<LockoutResult.Locked>()
        result2.shouldBe(LockoutResult.NotLocked)
    }
})
