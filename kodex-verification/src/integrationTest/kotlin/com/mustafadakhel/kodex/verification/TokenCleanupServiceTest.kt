@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.eq
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
import org.h2.jdbcx.JdbcDataSource
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
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:v_cleanup_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        schema = VerificationSchema(core.prefix)
        db = KodexDatabase(
            dataSource = ds,
            dialect = DatabaseDialect.H2,
            core = core,
            extensionSchemas = mapOf(VerificationSchema::class to schema)
        )
        db.createSchema()
        testSetup = TestDatabaseSetup(db)

        cleanupService = DefaultTokenCleanupService(db, schema, timeZone, null, "test-realm")
    }

    context("Token Cleanup") {
        test("should delete used tokens older than retention period") {
            val userId = testSetup.createTestUser(email = "cleanup@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val recentDate = now.minus(29.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                insertInto(tokens) {
                    set(tokens.realmId, "test-realm")
                    set(tokens.userId, userId)
                    set(tokens.contactType, "email")
                    set(tokens.token, TokenHasher.hash("old-used-token"))
                    set(tokens.createdAt, oldDate)
                    set(tokens.expiresAt, now.plus(1.hours).toLocalDateTime(timeZone))
                    set(tokens.usedAt, oldDate)
                }

                insertInto(tokens) {
                    set(tokens.realmId, "test-realm")
                    set(tokens.userId, userId)
                    set(tokens.contactType, "email")
                    set(tokens.token, TokenHasher.hash("recent-used-token"))
                    set(tokens.createdAt, recentDate)
                    set(tokens.expiresAt, now.plus(1.hours).toLocalDateTime(timeZone))
                    set(tokens.usedAt, recentDate)
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 1

            db.transaction {
                val remainingTokens = select(tokens).map { it[tokens.token] }
                remainingTokens shouldBe listOf(TokenHasher.hash("recent-used-token"))
            }
        }

        test("should not delete active tokens") {
            val userId = testSetup.createTestUser(email = "active@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val recentDate = now.minus(1.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                insertInto(tokens) {
                    set(tokens.realmId, "test-realm")
                    set(tokens.userId, userId)
                    set(tokens.contactType, "email")
                    set(tokens.token, TokenHasher.hash("active-token"))
                    set(tokens.createdAt, recentDate)
                    set(tokens.expiresAt, now.plus(23.hours).toLocalDateTime(timeZone))
                    set(tokens.usedAt, null)
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
                insertInto(tokens) {
                    set(tokens.realmId, "test-realm")
                    set(tokens.userId, userId)
                    set(tokens.contactType, "email")
                    set(tokens.token, TokenHasher.hash("old-email-token"))
                    set(tokens.createdAt, oldDate)
                    set(tokens.expiresAt, now.plus(1.hours).toLocalDateTime(timeZone))
                    set(tokens.usedAt, oldDate)
                }

                insertInto(tokens) {
                    set(tokens.realmId, "test-realm")
                    set(tokens.userId, userId)
                    set(tokens.contactType, "phone")
                    set(tokens.token, TokenHasher.hash("old-phone-token"))
                    set(tokens.createdAt, oldDate)
                    set(tokens.expiresAt, now.plus(1.hours).toLocalDateTime(timeZone))
                    set(tokens.usedAt, oldDate)
                }

                insertInto(tokens) {
                    set(tokens.realmId, "test-realm")
                    set(tokens.userId, userId)
                    set(tokens.contactType, "discord")
                    set(tokens.token, TokenHasher.hash("old-custom-token"))
                    set(tokens.createdAt, oldDate)
                    set(tokens.expiresAt, now.plus(1.hours).toLocalDateTime(timeZone))
                    set(tokens.usedAt, oldDate)
                }
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 3

            db.transaction {
                select(tokens).count() shouldBe 0
            }
        }

        test("STRESS TEST: should handle 2500+ tokens with batched deletion") {
            val userId = testSetup.createTestUser(email = "stress@test.com", realmId = "test-realm")
            val now = CurrentKotlinInstant
            val oldDate = now.minus(31.days).toLocalDateTime(timeZone)
            val tokens = schema.verificationTokens

            db.transaction {
                repeat(2500) { index ->
                    insertInto(tokens) {
                        set(tokens.realmId, "test-realm")
                        set(tokens.userId, userId)
                        set(tokens.contactType, "email")
                        set(tokens.token, TokenHasher.hash("expired-token-$index"))
                        set(tokens.createdAt, oldDate)
                        set(tokens.expiresAt, now.minus(2.days).toLocalDateTime(timeZone))
                        set(tokens.usedAt, null)
                    }
                }
            }

            db.transaction {
                select(tokens).count() shouldBe 2500
            }

            val deletedCount = cleanupService.purgeExpiredTokens(retentionPeriod = 30.days)

            deletedCount shouldBe 2500

            db.transaction {
                select(tokens).count() shouldBe 0
            }
        }
    }
})
