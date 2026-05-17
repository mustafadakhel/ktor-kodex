@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.mfa.schema.MfaSchema
import com.mustafadakhel.kodex.mfa.session.MfaSessionStore
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MfaCleanupServiceTest : FunSpec({

    lateinit var db: KodexDatabase
    lateinit var schema: MfaSchema
    lateinit var cleanupService: DefaultMfaCleanupService
    lateinit var testUserId: UUID
    val realmId = "test-realm"
    val timeZone = TimeZone.UTC

    beforeEach {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:mfa_cleanup_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        schema = MfaSchema(core.prefix)
        db = KodexDatabase(
            dataSource = ds,
            dialect = DatabaseDialect.H2,
            core = core,
            extensionSchemas = mapOf(MfaSchema::class to schema)
        )
        db.createSchema()

        val testSetup = TestDatabaseSetup(db)
        testUserId = testSetup.createTestUser(
            email = "test@example.com",
            passwordHash = "hash"
        )

        val sessionStore = MfaSessionStore(
            sessionExpiration = 5.minutes,
            maxActiveSessions = 3
        )

        cleanupService = DefaultMfaCleanupService(
            db = db,
            schema = schema,
            realmId = realmId,
            timeZone = timeZone,
            sessionStore = sessionStore,
            inactiveEnrollmentExpiration = 24.hours
        )
    }

    test("cleanupExpiredChallenges removes expired but keeps active") {
        val now = Clock.System.now()
        val past = (now - 1.hours).toLocalDateTime(timeZone)
        val future = (now + 1.hours).toLocalDateTime(timeZone)

        val challenges = schema.mfaChallenges
        val methods = schema.mfaMethods

        db.transaction {
            // Insert a method for FK
            val methodId = insertReturningKey(methods, methods.id) {
                set(methods.realmId, realmId)
                set(methods.userId, testUserId)
                set(methods.methodType, MfaMethodType.EMAIL)
                set(methods.identifier, "test@test.com")
                set(methods.isActive, true)
                set(methods.isPrimary, true)
                set(methods.enrolledAt, now.toLocalDateTime(timeZone))
            }

            // Expired challenge
            insertInto(challenges) {
                set(challenges.realmId, realmId)
                set(challenges.userId, testUserId)
                set(challenges.methodId, methodId)
                set(challenges.codeHash, "expired-hash")
                set(challenges.expiresAt, past)
                set(challenges.createdAt, past)
                set(challenges.attempts, 0)
                set(challenges.maxAttempts, 5)
            }

            // Active challenge (not yet expired)
            insertInto(challenges) {
                set(challenges.realmId, realmId)
                set(challenges.userId, testUserId)
                set(challenges.methodId, methodId)
                set(challenges.codeHash, "active-hash")
                set(challenges.expiresAt, future)
                set(challenges.createdAt, now.toLocalDateTime(timeZone))
                set(challenges.attempts, 0)
                set(challenges.maxAttempts, 5)
            }
        }

        val deleted = cleanupService.cleanupExpiredChallenges()
        deleted shouldBe 1

        // Verify active challenge remains
        val remaining = db.transaction {
            select(challenges)
                .where { (challenges.realmId eq realmId) and (challenges.userId eq testUserId) }
                .count()
        }
        remaining shouldBe 1
    }

    test("cleanupAbandonedEnrollments removes inactive TOTP but keeps active methods") {
        val now = Clock.System.now()
        val longAgo = (now - 48.hours).toLocalDateTime(timeZone)
        val recent = now.toLocalDateTime(timeZone)

        val methods = schema.mfaMethods

        db.transaction {
            // Inactive TOTP enrolled long ago — should be cleaned
            insertInto(methods) {
                set(methods.realmId, realmId)
                set(methods.userId, testUserId)
                set(methods.methodType, MfaMethodType.TOTP)
                set(methods.identifier, "TOTP")
                set(methods.isActive, false)
                set(methods.isPrimary, false)
                set(methods.enrolledAt, longAgo)
            }

            // Active TOTP enrolled long ago — should NOT be cleaned
            insertInto(methods) {
                set(methods.realmId, realmId)
                set(methods.userId, testUserId)
                set(methods.methodType, MfaMethodType.TOTP)
                set(methods.identifier, "TOTP-active")
                set(methods.isActive, true)
                set(methods.isPrimary, true)
                set(methods.enrolledAt, longAgo)
            }

            // Inactive TOTP enrolled recently — should NOT be cleaned (within expiration window)
            insertInto(methods) {
                set(methods.realmId, realmId)
                set(methods.userId, testUserId)
                set(methods.methodType, MfaMethodType.TOTP)
                set(methods.identifier, "TOTP-recent")
                set(methods.isActive, false)
                set(methods.isPrimary, false)
                set(methods.enrolledAt, recent)
            }
        }

        val deleted = cleanupService.cleanupAbandonedEnrollments()
        deleted shouldBe 1

        // Verify active + recent enrollments remain
        val remaining = db.transaction {
            select(methods)
                .where { (methods.realmId eq realmId) and (methods.userId eq testUserId) }
                .count()
        }
        remaining shouldBe 2
    }
})
