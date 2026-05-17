package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.test.TestDatabaseSetup
import com.mustafadakhel.kodex.util.now
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.random.Random

/**
 * Concurrency tests for TokenRepository to validate TOCTOU fixes.
 *
 * Tests atomic UPDATE operations for:
 * - revokeToken(tokenHash)
 * - revokeTokens(userId)
 * - revokeTokenFamily(tokenFamily)
 * - markTokenAsUsedIfUnused(tokenId)
 */
class TokenRepositoryConcurrencyTest : FunSpec({
    lateinit var repository: TokenRepository
    lateinit var db: KodexDatabase
    lateinit var testSetup: TestDatabaseSetup
    val testRealm = "test-realm"

    beforeEach {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:token_toctou_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        db = KodexDatabase(ds, DatabaseDialect.H2, core)
        db.createSchema()
        testSetup = TestDatabaseSetup(db)
        repository = databaseTokenRepository(db, testRealm)
    }

    context("Concurrent Token Revocation by Hash") {
        test("should handle concurrent revoke attempts on same token") {
            val userId = testSetup.createTestUser(email = "revoke-hash@test.com", realmId = testRealm)
            val tokenHash = "test-token-hash-${Random.nextInt()}"
            val now = now(TimeZone.UTC)
            val tokenId = repository.storeToken(
                PersistedToken(
                    id = UUID.randomUUID(), userId = userId, tokenHash = tokenHash,
                    type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                    tokenFamily = null, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                    realmId = testRealm
                )
            )

            withContext(Dispatchers.Default) {
                List(100) { async { repository.revokeToken(tokenHash) } }.awaitAll()
            }

            val token = repository.findToken(tokenId)
            token?.revoked shouldBe true
        }

        test("should handle concurrent revocations of different tokens") {
            val userId = testSetup.createTestUser(email = "revoke-diff@test.com", realmId = testRealm)
            val now = now(TimeZone.UTC)
            val tokens = List(50) { index ->
                val tokenHash = "concurrent-token-$index"
                val tokenId = repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(), userId = userId, tokenHash = tokenHash,
                        type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                        tokenFamily = null, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                        realmId = testRealm
                    )
                )
                tokenHash to tokenId
            }

            withContext(Dispatchers.Default) {
                tokens.map { (tokenHash, _) -> async { repository.revokeToken(tokenHash) } }.awaitAll()
            }

            tokens.forEach { (_, tokenId) ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }
    }

    context("Concurrent Token Revocation by User ID") {
        test("should atomically revoke all user tokens under concurrent access") {
            val userId = testSetup.createTestUser(email = "revoke-user@test.com", realmId = testRealm)
            val now = now(TimeZone.UTC)

            val tokenIds = List(20) { index ->
                repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(), userId = userId, tokenHash = "user-token-$index",
                        type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                        tokenFamily = null, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                        realmId = testRealm
                    )
                )
            }

            withContext(Dispatchers.Default) {
                List(50) { async { repository.revokeTokens(userId) } }.awaitAll()
            }

            tokenIds.forEach { tokenId ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }

        test("should handle concurrent revocations across multiple users") {
            val users = List(10) { i ->
                testSetup.createTestUser(email = "multi-user-$i@test.com", realmId = testRealm)
            }
            val now = now(TimeZone.UTC)

            val allTokens = users.flatMap { userId ->
                List(5) { index ->
                    val tokenId = repository.storeToken(
                        PersistedToken(
                            id = UUID.randomUUID(), userId = userId,
                            tokenHash = "user-${userId}-token-$index",
                            type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                            tokenFamily = null, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                            realmId = testRealm
                        )
                    )
                    userId to tokenId
                }
            }

            withContext(Dispatchers.Default) {
                users.map { userId -> async { repository.revokeTokens(userId) } }.awaitAll()
            }

            allTokens.forEach { (_, tokenId) ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }
    }

    context("Concurrent Token Family Revocation") {
        test("should atomically revoke entire token family under concurrent access") {
            val userId = testSetup.createTestUser(email = "family@test.com", realmId = testRealm)
            val tokenFamily = UUID.randomUUID()
            val now = now(TimeZone.UTC)

            val tokenIds = List(15) { index ->
                repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(), userId = userId, tokenHash = "family-token-$index",
                        type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                        tokenFamily = tokenFamily,
                        parentTokenId = if (index > 0) UUID.randomUUID() else null,
                        firstUsedAt = null, lastUsedAt = null, realmId = testRealm
                    )
                )
            }

            withContext(Dispatchers.Default) {
                List(75) { async { repository.revokeTokenFamily(tokenFamily) } }.awaitAll()
            }

            val familyTokens = repository.findTokensByFamily(tokenFamily)
            familyTokens shouldHaveSize 15
            familyTokens.forEach { token -> token.revoked shouldBe true }
        }
    }

    context("Concurrent markTokenAsUsedIfUnused") {
        test("should only mark token as used once despite concurrent attempts") {
            val userId = testSetup.createTestUser(email = "mark-used@test.com", realmId = testRealm)
            val now = now(TimeZone.UTC)
            val tokenId = repository.storeToken(
                PersistedToken(
                    id = UUID.randomUUID(), userId = userId, tokenHash = "unused-token",
                    type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                    tokenFamily = null, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                    realmId = testRealm
                )
            )

            val results = withContext(Dispatchers.Default) {
                List(100) { async { repository.markTokenAsUsedIfUnused(tokenId, now) } }.awaitAll()
            }

            val successCount = results.count { it }
            successCount shouldBe 1

            val token = repository.findToken(tokenId)
            token?.firstUsedAt shouldNotBe null
            token?.lastUsedAt shouldNotBe null
        }

        test("should handle concurrent marking across multiple tokens") {
            val userId = testSetup.createTestUser(email = "mark-multi@test.com", realmId = testRealm)
            val now = now(TimeZone.UTC)

            val tokenIds = List(30) { index ->
                repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(), userId = userId,
                        tokenHash = "concurrent-unused-$index",
                        type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                        tokenFamily = null, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                        realmId = testRealm
                    )
                )
            }

            val results = withContext(Dispatchers.Default) {
                tokenIds.flatMap { tokenId ->
                    List(10) { async { tokenId to repository.markTokenAsUsedIfUnused(tokenId, now) } }
                }.awaitAll()
            }

            results.groupBy { it.first }.forEach { (tokenId, attempts) ->
                val successCount = attempts.count { it.second }
                successCount shouldBe 1

                val token = repository.findToken(tokenId)
                token?.firstUsedAt shouldNotBe null
            }
        }
    }

    context("Stress Test - High Concurrency") {
        test("should handle extreme concurrent load without data corruption") {
            val userIds = List(5) { i ->
                testSetup.createTestUser(email = "stress-$i@test.com", realmId = testRealm)
            }
            val families = List(5) { UUID.randomUUID() }
            val now = now(TimeZone.UTC)

            val tokenData = List(100) { index ->
                val userId = userIds[index % userIds.size]
                val family = if (index % 3 == 0) families[index % families.size] else null
                val tokenId = repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(), userId = userId,
                        tokenHash = "stress-token-$index",
                        type = TokenType.RefreshToken, revoked = false, createdAt = now, expiresAt = now,
                        tokenFamily = family, parentTokenId = null, firstUsedAt = null, lastUsedAt = null,
                        realmId = testRealm
                    )
                )
                Triple(tokenId, userId, family)
            }

            withContext(Dispatchers.Default) {
                List(200) { opIndex ->
                    async {
                        when (opIndex % 4) {
                            0 -> {
                                val userId = userIds[opIndex % userIds.size]
                                repository.revokeTokens(userId)
                            }
                            1 -> {
                                val family = families[opIndex % families.size]
                                repository.revokeTokenFamily(family)
                            }
                            2 -> {
                                val (tokenId, _, _) = tokenData[opIndex % tokenData.size]
                                val token = repository.findToken(tokenId)
                                token?.let { repository.revokeToken(it.tokenHash) }
                            }
                            else -> {
                                val (tokenId, _, _) = tokenData[opIndex % tokenData.size]
                                repository.markTokenAsUsedIfUnused(tokenId, now)
                            }
                        }
                    }
                }.awaitAll()
            }

            tokenData.forEach { (tokenId, _, _) ->
                val token = repository.findToken(tokenId)
                token shouldNotBe null
            }
        }
    }
})
