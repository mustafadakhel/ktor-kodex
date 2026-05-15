package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import com.mustafadakhel.kodex.tokens.token.TokenHasher
import com.mustafadakhel.kodex.verification.schema.VerificationSchema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class TokenCleanupServiceTest : FunSpec({

    lateinit var cleanupService: TokenCleanupService
    lateinit var db: KodexDatabase
    lateinit var schema: VerificationSchema
    lateinit var testSetup: TestDatabaseSetup
    val timeZone = TimeZone.UTC

    beforeEach {
        val database = Database.connect(
            url = "jdbc:h2:mem:v_cleanup_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        val core = CoreSchema("test_")
        schema = VerificationSchema(core)
        db = KodexDatabase(database, core, mapOf(VerificationSchema::class to schema))
        db.createSchema()
        testSetup = TestDatabaseSetup(db)

        cleanupService = DefaultTokenCleanupService(db, schema, timeZone, null, "test-realm")
    }

    afterEach {
        db.transaction {
            SchemaUtils.drop(schema.verificationTokens, schema.verifiableContacts)
        }
    }

    context("Token Cleanup") {
        test("should delete used tokens older than retention period") {
            val userId = testSetup.createTestUser(email = "cleanup@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val recentDate = now.minus(29.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[tokens.contactType] = "email"
                    it[tokens.token] = TokenHasher.hash("old-used-token")
                    it[tokens.createdAt] = oldDate
                    it[tokens.expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[tokens.usedAt] = oldDate
                }

                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[tokens.contactType] = "email"
                    it[tokens.token] = TokenHasher.hash("recent-used-token")
                    it[tokens.createdAt] = recentDate
                    it[tokens.expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[tokens.usedAt] = recentDate
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 1

            db.transaction {
                val remainingTokens = tokens.selectAll().map { it[tokens.token] }
                remainingTokens shouldBe listOf(TokenHasher.hash("recent-used-token"))
            }
        }

        test("should not delete active tokens") {
            val userId = testSetup.createTestUser(email = "active@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val recentDate = now.minus(1.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[tokens.contactType] = "email"
                    it[tokens.token] = TokenHasher.hash("active-token")
                    it[tokens.createdAt] = recentDate
                    it[tokens.expiresAt] = now.plus(23.hours).toLocalDateTime(timeZone)
                    it[tokens.usedAt] = null
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 0
        }

        test("should handle empty token table") {
            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)
            deletedCount shouldBe 0
        }

        test("should handle multiple token types") {
            val userId = testSetup.createTestUser(email = "multi-type@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[tokens.contactType] = "email"
                    it[tokens.token] = TokenHasher.hash("old-email-token")
                    it[tokens.createdAt] = oldDate
                    it[tokens.expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[tokens.usedAt] = oldDate
                }

                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[tokens.contactType] = "phone"
                    it[tokens.token] = TokenHasher.hash("old-phone-token")
                    it[tokens.createdAt] = oldDate
                    it[tokens.expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[tokens.usedAt] = oldDate
                }

                tokens.insert {
                    it[tokens.realmId] = "test-realm"
                    it[tokens.userId] = userId
                    it[tokens.contactType] = "discord"
                    it[tokens.token] = TokenHasher.hash("old-custom-token")
                    it[tokens.createdAt] = oldDate
                    it[tokens.expiresAt] = now.plus(1.hours).toLocalDateTime(timeZone)
                    it[tokens.usedAt] = oldDate
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 3

            db.transaction {
                tokens.selectAll().count() shouldBe 0
            }
        }

        test("STRESS TEST: should handle 2500+ tokens with batched deletion") {
            val userId = testSetup.createTestUser(email = "stress@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                repeat(2500) { index ->
                    tokens.insert {
                        it[tokens.realmId] = "test-realm"
                        it[tokens.userId] = userId
                        it[tokens.contactType] = "email"
                        it[tokens.token] = TokenHasher.hash("expired-token-$index")
                        it[tokens.createdAt] = oldDate
                        it[tokens.expiresAt] = now.minus(2.days).toLocalDateTime(timeZone)
                        it[tokens.usedAt] = null
                    }
                }
            }

            db.transaction {
                tokens.selectAll().count() shouldBe 2500
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 2500

            db.transaction {
                tokens.selectAll().count() shouldBe 0
            }
        }
    }
})
