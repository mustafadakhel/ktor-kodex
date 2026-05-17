@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.token

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.token.cleanup.TokenCleanupRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class TokenCleanupRepositoryTest : FunSpec({

    val realmId = "cleanup-test"

    lateinit var db: KodexDatabase
    lateinit var cleanupRepo: TokenCleanupRepository

    beforeEach {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:cleanup_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        db = KodexDatabase(ds, DatabaseDialect.H2, core)
        db.createSchema()

        val userRepository = databaseUserRepository(db, realmId)
        val tokenRepository = databaseTokenRepository(db, realmId)

        // Seed a user
        db.transaction {
            userRepository.seedRoles(listOf(Role(realmId, "Test realm")))
            userRepository.create(
                email = "test@test.com",
                phone = null,
                hashedPassword = "hashed",
                roleNames = listOf(realmId),
                currentTime = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.UTC),
                customAttributes = null,
                profile = null
            )
        }

        cleanupRepo = TokenCleanupRepository(db, realmId)
    }

    fun insertToken(
        realmId: String,
        userId: UUID,
        expiresAt: LocalDateTime,
        revoked: Boolean = false,
        tokenFamily: UUID? = null
    ) {
        val tokens = db.core.tokens
        db.transaction {
            insertInto(tokens) {
                set(tokens.userId, userId)
                set(tokens.tokenHash, UUID.randomUUID().toString())
                set(tokens.type, "refresh")
                set(tokens.revoked, revoked)
                set(tokens.createdAt, expiresAt) // use same time for simplicity
                set(tokens.expiresAt, expiresAt)
                set(tokens.tokenFamily, tokenFamily)
                set(tokens.realmId, realmId)
            }
        }
    }

    fun getUserId(): UUID {
        return db.transaction {
            select(db.core.users).map { row -> row[db.core.users.id] }.first()
        }
    }

    test("deleteExpiredTokens only deletes tokens expired before cutoff") {
        val userId = getUserId()
        val past = kotlinx.datetime.Clock.System.now().minus(2.hours).toLocalDateTime(TimeZone.UTC)
        val future = kotlinx.datetime.Clock.System.now().plus(2.hours).toLocalDateTime(TimeZone.UTC)
        val cutoff = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.UTC)

        insertToken(realmId, userId, past) // expired — should be deleted
        insertToken(realmId, userId, future) // not expired — should remain

        val deleted = cleanupRepo.deleteExpiredTokens(cutoff)

        deleted shouldBe 1
    }

    test("deleteExpiredTokens scopes to realm") {
        val userId = getUserId()
        val past = kotlinx.datetime.Clock.System.now().minus(2.hours).toLocalDateTime(TimeZone.UTC)
        val cutoff = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.UTC)

        insertToken(realmId, userId, past)
        insertToken("other-realm", userId, past) // different realm

        val deleted = cleanupRepo.deleteExpiredTokens(cutoff)

        deleted shouldBe 1 // only our realm's token
    }

    test("deleteRevokedTokens only deletes revoked tokens before cutoff") {
        val userId = getUserId()
        val past = kotlinx.datetime.Clock.System.now().minus(2.hours).toLocalDateTime(TimeZone.UTC)
        val cutoff = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.UTC)

        insertToken(realmId, userId, past, revoked = true) // revoked + old — deleted
        insertToken(realmId, userId, past, revoked = false) // not revoked — kept

        val deleted = cleanupRepo.deleteRevokedTokens(cutoff)

        deleted shouldBe 1
    }

    test("deleteRevokedFamilyTokens targets revoked tokens with token family") {
        val userId = getUserId()
        val past = kotlinx.datetime.Clock.System.now().minus(2.hours).toLocalDateTime(TimeZone.UTC)
        val cutoff = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val family = UUID.randomUUID()

        insertToken(realmId, userId, past, revoked = true, tokenFamily = family) // target
        insertToken(realmId, userId, past, revoked = true, tokenFamily = null) // no family — excluded
        insertToken(realmId, userId, past, revoked = false, tokenFamily = family) // not revoked — excluded

        val deleted = cleanupRepo.deleteRevokedFamilyTokens(cutoff)

        deleted shouldBe 1
    }
})
