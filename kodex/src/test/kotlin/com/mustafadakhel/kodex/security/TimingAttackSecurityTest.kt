package com.mustafadakhel.kodex.security

import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.service.KodexRealmService
import com.mustafadakhel.kodex.service.KodexService
import com.mustafadakhel.kodex.service.argon2HashingService
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.deleteAll
import kotlin.system.measureNanoTime
import kotlin.math.abs
import kotlin.math.sqrt

class TimingAttackSecurityTest : FunSpec({

    lateinit var service: KodexService
    lateinit var userRepository: UserRepository
    val realm = Realm("test-realm")

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:timing-test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = false)

        userRepository = databaseUserRepository()

        exposedTransaction {
            userRepository.seedRoles(listOf(
                Role(realm.owner, "Realm owner role"),
                Role("USER", "Standard user")
            ))
        }

        val tokenManager = mockk<TokenManager>(relaxed = true)
        coEvery { tokenManager.issueNewTokens(any()) } returns TokenPair(
            access = "mock-access-token",
            refresh = "mock-refresh-token"
        )

        service = KodexRealmService(
            userRepository = userRepository,
            tokenManager = tokenManager,
            hashingService = argon2HashingService(),
            timeZone = TimeZone.UTC,
            realm = realm,
            extensions = ExtensionRegistry.empty()
        )
    }

    afterEach {
        exposedTransaction {
            UserRoles.deleteAll()
            UserCustomAttributes.deleteAll()
            UserProfiles.deleteAll()
            Tokens.deleteAll()
            Users.deleteAll()
            Roles.deleteAll()
        }
        Db.clearEngine()
    }

    context("Timing Attack Mitigation") {
        test("should have similar timing for valid and invalid users") {

            val user = service.createUser(
                email = "timing@example.com",
                phone = null,
                password = "SecurePass123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!
            service.setVerified(user.id, true)

            val validUserTimes = mutableListOf<Long>()
            val invalidUserTimes = mutableListOf<Long>()

            // Warm-up to eliminate JIT compilation effects
            repeat(10) {
                runCatching { service.tokenByEmail("timing@example.com", "WrongPassword") }
                runCatching { service.tokenByEmail("nonexistent@example.com", "WrongPassword") }
            }

            // Measure authentication timing for existing user (wrong password)
            repeat(100) {
                val time = measureNanoTime {
                    runCatching { service.tokenByEmail("timing@example.com", "WrongPassword") }
                }
                validUserTimes.add(time)
            }

            // Measure authentication timing for non-existent user
            repeat(100) {
                val time = measureNanoTime {
                    runCatching { service.tokenByEmail("nonexistent@example.com", "WrongPassword") }
                }
                invalidUserTimes.add(time)
            }

            // Calculate statistics
            val validUserAvg = validUserTimes.average()
            val invalidUserAvg = invalidUserTimes.average()
            val validUserStdDev = standardDeviation(validUserTimes)
            val invalidUserStdDev = standardDeviation(invalidUserTimes)

            // The timing difference should be within noise threshold
            // We allow up to 2 standard deviations as acceptable variance
            val timingDifference = abs(validUserAvg - invalidUserAvg)
            val combinedStdDev = sqrt(validUserStdDev * validUserStdDev + invalidUserStdDev * invalidUserStdDev)
            val normalizedDifference = timingDifference / combinedStdDev

            // Assert that timing difference is not statistically significant
            // A normalized difference < 2.0 means the difference is within normal variance
            normalizedDifference shouldBeLessThan 2.0
        }

        test("should always execute password verification for non-existent users") {
            val timings = mutableListOf<Long>()

            // Warm-up
            repeat(10) {
                runCatching { service.tokenByEmail("ghost@example.com", "AnyPassword") }
            }

            repeat(50) {
                val time = measureNanoTime {
                    runCatching { service.tokenByEmail("ghost@example.com", "AnyPassword") }
                }
                timings.add(time)
            }

            val avgTime = timings.average()

            // Password hashing with Argon2id should take at least 10ms (10,000,000 ns)
            // If it's significantly faster, it means verification is being skipped
            (avgTime > 10_000_000) shouldBe true
        }

        test("should maintain constant-time behavior across multiple authentication attempts") {
            val user = service.createUser(
                email = "constant@example.com",
                phone = null,
                password = "Password123",
                roleNames = emptyList(),
                customAttributes = emptyMap(),
                profile = null
            )!!
            service.setVerified(user.id, true)

            val attempt1Times = mutableListOf<Long>()
            val attempt2Times = mutableListOf<Long>()
            val attempt3Times = mutableListOf<Long>()

            // Warm-up
            repeat(10) {
                runCatching { service.tokenByEmail("constant@example.com", "Wrong1") }
                runCatching { service.tokenByEmail("nonexist1@example.com", "Wrong2") }
                runCatching { service.tokenByEmail("nonexist2@example.com", "Wrong3") }
            }

            // Measure three different scenarios
            repeat(50) {
                attempt1Times.add(measureNanoTime {
                    runCatching { service.tokenByEmail("constant@example.com", "Wrong1") }
                })

                attempt2Times.add(measureNanoTime {
                    runCatching { service.tokenByEmail("nonexist1@example.com", "Wrong2") }
                })

                attempt3Times.add(measureNanoTime {
                    runCatching { service.tokenByEmail("nonexist2@example.com", "Wrong3") }
                })
            }

            val avg1 = attempt1Times.average()
            val avg2 = attempt2Times.average()
            val avg3 = attempt3Times.average()

            // All three scenarios should have similar average timing
            val maxDiff = maxOf(
                abs(avg1 - avg2),
                abs(avg2 - avg3),
                abs(avg1 - avg3)
            )

            // Allow up to 30% variance (this is generous to account for system noise)
            val allowedVariance = avg1 * 0.30
            maxDiff shouldBeLessThan allowedVariance
        }

        test("should not leak user existence through timing with high confidence") {
            // Create multiple users
            val users = (1..5).map { i ->
                service.createUser(
                    email = "user$i@example.com",
                    phone = null,
                    password = "Pass$i",
                    roleNames = emptyList(),
                    customAttributes = emptyMap(),
                    profile = null
                )!!.also { service.setVerified(it.id, true) }
            }

            // Warm-up
            repeat(20) {
                runCatching { service.tokenByEmail("user1@example.com", "WrongPass") }
                runCatching { service.tokenByEmail("ghost@example.com", "WrongPass") }
            }

            // Collect timing data
            val existingUserTimes = mutableListOf<Long>()
            val nonExistentUserTimes = mutableListOf<Long>()

            repeat(200) {
                // Test existing user
                val existingTime = measureNanoTime {
                    runCatching { service.tokenByEmail("user${(it % 5) + 1}@example.com", "WrongPass") }
                }
                existingUserTimes.add(existingTime)

                // Test non-existent user
                val nonExistentTime = measureNanoTime {
                    runCatching { service.tokenByEmail("ghost${it}@example.com", "WrongPass") }
                }
                nonExistentUserTimes.add(nonExistentTime)
            }

            val existingAvg = existingUserTimes.average()
            val nonExistentAvg = nonExistentUserTimes.average()
            val existingStdDev = standardDeviation(existingUserTimes)
            val nonExistentStdDev = standardDeviation(nonExistentUserTimes)

            // Statistical significance test
            val pooledStdDev = sqrt(
                (existingStdDev * existingStdDev + nonExistentStdDev * nonExistentStdDev) / 2
            )
            val standardError = pooledStdDev * sqrt(2.0 / 200.0)
            val tStatistic = abs(existingAvg - nonExistentAvg) / standardError

            // For statistical significance, we use t-value threshold of 5.0
            // This is more lenient than traditional 1.96 (95% CI) or 3.0 (99.7% CI)
            // to account for system timing variance, CPU scheduling, and GC pauses
            // The goal is to detect EXPLOITABLE timing leaks, not minor variance
            tStatistic shouldBeLessThan 5.0
        }
    }
})

private fun standardDeviation(values: List<Long>): Double {
    val mean = values.average()
    val variance = values.map { (it - mean) * (it - mean) }.average()
    return sqrt(variance)
}
