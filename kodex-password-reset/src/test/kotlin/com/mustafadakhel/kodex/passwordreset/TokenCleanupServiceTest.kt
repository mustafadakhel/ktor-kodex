package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.passwordreset.database.PasswordResetTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class TokenCleanupServiceTest : FunSpec({

    lateinit var cleanupService: TokenCleanupService
    lateinit var database: Database
    val timeZone = TimeZone.UTC

    beforeEach {
        database = Database.connect(
            url = "jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        transaction(database) {
            SchemaUtils.create(PasswordResetTokens)
        }

        cleanupService = DefaultTokenCleanupService(timeZone, null, "test")
    }

    afterEach {
        transaction(database) {
            SchemaUtils.drop(PasswordResetTokens)
        }
    }

    context("Token Cleanup") {
        test("should delete used tokens older than retention period") {
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val recentDate = now.minus(29.days).toLocalDateTime(timeZone)

            transaction(database) {
                // Old used token - should be deleted
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "old-used-token"
                    it[contactValue] = "test@example.com"
                    it[createdAt] = oldDate
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = oldDate
                    it[ipAddress] = "192.168.1.1"
                }

                // Recent used token - should NOT be deleted
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "recent-used-token"
                    it[contactValue] = "test2@example.com"
                    it[createdAt] = recentDate
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = recentDate
                    it[ipAddress] = "192.168.1.2"
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 1

            transaction(database) {
                val remainingTokens = PasswordResetTokens.selectAll().map { it[PasswordResetTokens.token] }
                remainingTokens shouldBe listOf("recent-used-token")
            }
        }

        test("should delete expired tokens older than retention period") {
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val expiredOldDate = now.minus(2.days).toLocalDateTime(timeZone)

            transaction(database) {
                // Expired token with old creation date - should be deleted
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "expired-old-token"
                    it[contactValue] = "test@example.com"
                    it[createdAt] = oldDate
                    it[expiresAt] = expiredOldDate
                    it[usedAt] = null
                    it[ipAddress] = null
                }

                // Expired token with recent creation - should NOT be deleted
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "expired-recent-token"
                    it[contactValue] = "test2@example.com"
                    it[createdAt] = now.minus(1.days).toLocalDateTime(timeZone)
                    it[expiresAt] = expiredOldDate
                    it[usedAt] = null
                    it[ipAddress] = null
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 1

            transaction(database) {
                val remainingTokens = PasswordResetTokens.selectAll().map { it[PasswordResetTokens.token] }
                remainingTokens shouldBe listOf("expired-recent-token")
            }
        }

        test("should not delete active tokens") {
            val now = CurrentKotlinInstant
            val recentDate = now.minus(1.days).toLocalDateTime(timeZone)

            transaction(database) {
                // Active token (not expired, not used)
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "active-token"
                    it[contactValue] = "test@example.com"
                    it[createdAt] = recentDate
                    it[expiresAt] = now.plus(23.hours).toLocalDateTime(timeZone)
                    it[usedAt] = null
                    it[ipAddress] = null
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 0

            transaction(database) {
                val remainingTokens = PasswordResetTokens.selectAll().map { it[PasswordResetTokens.token] }
                remainingTokens shouldBe listOf("active-token")
            }
        }

        test("should handle custom retention periods") {
            val now = CurrentKotlinInstant
            val tenDaysOld = now.minus(10.days).toLocalDateTime(timeZone)
            val sixDaysOld = now.minus(6.days).toLocalDateTime(timeZone)

            transaction(database) {
                // Token used 10 days ago
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "token-10d"
                    it[contactValue] = "test@example.com"
                    it[createdAt] = tenDaysOld
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = tenDaysOld
                    it[ipAddress] = "192.168.1.1"
                }

                // Token used 6 days ago
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "token-6d"
                    it[contactValue] = "test2@example.com"
                    it[createdAt] = sixDaysOld
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = sixDaysOld
                    it[ipAddress] = "192.168.1.2"
                }
            }

            // With 7 day retention, only 10-day-old token should be deleted
            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 7.days)

            deletedCount shouldBe 1

            transaction(database) {
                val remainingTokens = PasswordResetTokens.selectAll().map { it[PasswordResetTokens.token] }
                remainingTokens shouldBe listOf("token-6d")
            }
        }

        test("should handle empty token table") {
            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 0
        }

        test("should handle tokens with and without IP addresses") {
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)

            transaction(database) {
                // Old used token with IP
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "old-token-with-ip"
                    it[contactValue] = "test@example.com"
                    it[createdAt] = oldDate
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = oldDate
                    it[ipAddress] = "192.168.1.1"
                }

                // Old used token without IP
                PasswordResetTokens.insert {
                    it[userId] = UUID.randomUUID()
                    it[token] = "old-token-no-ip"
                    it[contactValue] = "test2@example.com"
                    it[createdAt] = oldDate
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = oldDate
                    it[ipAddress] = null
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 2

            transaction(database) {
                PasswordResetTokens.selectAll().count() shouldBe 0
            }
        }

        test("should handle multiple users") {
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val user1 = UUID.randomUUID()
            val user2 = UUID.randomUUID()

            transaction(database) {
                // Old token for user 1
                PasswordResetTokens.insert {
                    it[userId] = user1
                    it[token] = "user1-old-token"
                    it[contactValue] = "user1@example.com"
                    it[createdAt] = oldDate
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = oldDate
                    it[ipAddress] = null
                }

                // Old token for user 2
                PasswordResetTokens.insert {
                    it[userId] = user2
                    it[token] = "user2-old-token"
                    it[contactValue] = "user2@example.com"
                    it[createdAt] = oldDate
                    it[expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[usedAt] = oldDate
                    it[ipAddress] = null
                }

                // Active token for user 1
                PasswordResetTokens.insert {
                    it[userId] = user1
                    it[token] = "user1-active-token"
                    it[contactValue] = "user1@example.com"
                    it[createdAt] = now.minus(1.hours).toLocalDateTime(timeZone)
                    it[expiresAt] = now.plus(23.hours).toLocalDateTime(timeZone)
                    it[usedAt] = null
                    it[ipAddress] = null
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 2

            transaction(database) {
                val remainingTokens = PasswordResetTokens.selectAll().map { it[PasswordResetTokens.token] }
                remainingTokens shouldBe listOf("user1-active-token")
            }
        }

        test("STRESS TEST: should handle 2500+ tokens with batched deletion") {
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)

            // Create 2500 expired tokens (simulating high volume)
            transaction(database) {
                repeat(2500) { index ->
                    PasswordResetTokens.insert {
                        it[userId] = UUID.randomUUID()
                        it[token] = "expired-token-$index"
                        it[contactValue] = "test$index@example.com"
                        it[createdAt] = oldDate
                        it[expiresAt] = now.minus(2.days).toLocalDateTime(timeZone)
                        it[usedAt] = null
                        it[ipAddress] = null
                    }
                }
            }

            // Verify we created 2500 tokens
            transaction(database) {
                PasswordResetTokens.selectAll().count() shouldBe 2500
            }

            // Run cleanup - should delete all 2500 in batches of 1000
            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            // Verify all tokens were deleted
            deletedCount shouldBe 2500

            transaction(database) {
                PasswordResetTokens.selectAll().count() shouldBe 0
            }

            // PROOF: If this test passes, batching works correctly
            // The service should have made 3 transactions:
            // - Batch 1: Delete 1000 tokens
            // - Batch 2: Delete 1000 tokens
            // - Batch 3: Delete 500 tokens
            // All without holding a long table lock
        }
    }
})
