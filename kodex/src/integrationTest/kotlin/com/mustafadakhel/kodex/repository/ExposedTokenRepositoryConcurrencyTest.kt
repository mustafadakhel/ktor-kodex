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
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import org.h2.jdbcx.JdbcDataSource
import java.util.*

/**
 * Concurrency tests for ExposedTokenRepository to verify atomic operations
 * and race condition handling.
 */
class ExposedTokenRepositoryConcurrencyTest : FunSpec({

    lateinit var tokenRepository: TokenRepository
    lateinit var db: KodexDatabase
    lateinit var testSetup: TestDatabaseSetup
    val testRealm = "test-realm"

    beforeEach {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:token_concurrency_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        db = KodexDatabase(ds, DatabaseDialect.H2, core)
        db.createSchema()
        testSetup = TestDatabaseSetup(db)
        tokenRepository = databaseTokenRepository(db, testRealm)
    }

    fun createToken(
        userId: UUID,
        tokenHash: String,
        tokenFamily: UUID = UUID.randomUUID(),
        revoked: Boolean = false
    ): PersistedToken {
        val now = now(TimeZone.UTC)
        return PersistedToken(
            id = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            type = TokenType.RefreshToken,
            revoked = revoked,
            createdAt = now,
            expiresAt = now,
            realmId = testRealm,
            tokenFamily = tokenFamily,
            parentTokenId = null,
            firstUsedAt = null,
            lastUsedAt = null
        )
    }

    context("Concurrent revokeToken(tokenHash)") {
        test("should handle concurrent revocations atomically") {
            val userId = testSetup.createTestUser(email = "revoke@test.com", realmId = testRealm)

            val tokenHash = "test-token-hash"
            val token = createToken(userId = userId, tokenHash = tokenHash)
            tokenRepository.storeToken(token)

            tokenRepository.findTokenByHash(tokenHash)!!.revoked shouldBe false

            withContext(Dispatchers.Default) {
                (1..10).map {
                    async { tokenRepository.revokeToken(tokenHash) }
                }.awaitAll()
            }

            val revokedToken = tokenRepository.findTokenByHash(tokenHash)!!
            revokedToken.revoked shouldBe true
        }

        test("should handle concurrent revocations of different tokens") {
            val userId = testSetup.createTestUser(email = "multi-revoke@test.com", realmId = testRealm)

            val tokens = (1..20).map { i ->
                createToken(userId = userId, tokenHash = "token-hash-$i").also {
                    tokenRepository.storeToken(it)
                }
            }

            withContext(Dispatchers.Default) {
                tokens.map { token ->
                    async { tokenRepository.revokeToken(token.tokenHash) }
                }.awaitAll()
            }

            tokens.forEach { token ->
                val revokedToken = tokenRepository.findTokenByHash(token.tokenHash)!!
                revokedToken.revoked shouldBe true
            }
        }
    }

    context("Concurrent revokeTokens(userId)") {
        test("should handle concurrent revocations by userId atomically") {
            val userId = testSetup.createTestUser(email = "user-revoke@test.com", realmId = testRealm)

            val tokens = (1..50).map { i ->
                createToken(userId = userId, tokenHash = "user-token-$i").also {
                    tokenRepository.storeToken(it)
                }
            }

            withContext(Dispatchers.Default) {
                (1..5).map {
                    async { tokenRepository.revokeTokens(userId) }
                }.awaitAll()
            }

            tokens.forEach { token ->
                val revokedToken = tokenRepository.findTokenByHash(token.tokenHash)!!
                revokedToken.revoked shouldBe true
            }
        }

        test("should handle concurrent revocations for different users") {
            val userIds = (1..10).map { i ->
                testSetup.createTestUser(email = "user$i@test.com", realmId = testRealm)
            }
            val tokensPerUser = 10

            userIds.forEach { userId ->
                (1..tokensPerUser).forEach { i ->
                    val token = createToken(userId = userId, tokenHash = "user-${userId}-token-$i")
                    tokenRepository.storeToken(token)
                }
            }

            withContext(Dispatchers.Default) {
                userIds.map { userId ->
                    async { tokenRepository.revokeTokens(userId) }
                }.awaitAll()
            }

            userIds.forEach { userId ->
                (1..tokensPerUser).forEach { i ->
                    val token = tokenRepository.findTokenByHash("user-${userId}-token-$i")!!
                    token.revoked shouldBe true
                }
            }
        }
    }

    context("Concurrent revokeTokenFamily(tokenFamily)") {
        test("should handle concurrent family revocations atomically") {
            val tokenFamily = UUID.randomUUID()
            val userId = testSetup.createTestUser(email = "family-revoke@test.com", realmId = testRealm)

            val tokens = (1..30).map { i ->
                createToken(userId = userId, tokenHash = "family-token-$i", tokenFamily = tokenFamily).also {
                    tokenRepository.storeToken(it)
                }
            }

            withContext(Dispatchers.Default) {
                (1..5).map {
                    async { tokenRepository.revokeTokenFamily(tokenFamily) }
                }.awaitAll()
            }

            tokens.forEach { token ->
                val revokedToken = tokenRepository.findTokenByHash(token.tokenHash)!!
                revokedToken.revoked shouldBe true
            }
        }

        test("should handle concurrent revocations of different families") {
            val families = (1..10).map { UUID.randomUUID() }
            val userId = testSetup.createTestUser(email = "multi-family@test.com", realmId = testRealm)

            val tokensPerFamily = 5

            families.forEach { familyId ->
                (1..tokensPerFamily).forEach { i ->
                    val token = createToken(
                        userId = userId,
                        tokenHash = "family-${familyId}-token-$i",
                        tokenFamily = familyId
                    )
                    tokenRepository.storeToken(token)
                }
            }

            withContext(Dispatchers.Default) {
                families.map { familyId ->
                    async { tokenRepository.revokeTokenFamily(familyId) }
                }.awaitAll()
            }

            families.forEach { familyId ->
                (1..tokensPerFamily).forEach { i ->
                    val token = tokenRepository.findTokenByHash("family-${familyId}-token-$i")!!
                    token.revoked shouldBe true
                }
            }
        }
    }

    context("Concurrent deleteToken(tokenHash)") {
        test("should handle concurrent deletions atomically") {
            val userId = testSetup.createTestUser(email = "delete-test@test.com", realmId = testRealm)

            val tokenHash = "delete-test-token"
            val token = createToken(userId = userId, tokenHash = tokenHash)
            tokenRepository.storeToken(token)

            val storedToken = tokenRepository.findTokenByHash(tokenHash)
            storedToken?.tokenHash shouldBe tokenHash
            storedToken?.userId shouldBe userId

            withContext(Dispatchers.Default) {
                (1..10).map {
                    async { tokenRepository.deleteToken(tokenHash) }
                }.awaitAll()
            }

            tokenRepository.findTokenByHash(tokenHash) shouldBe null
        }

        test("should handle concurrent deletions of different tokens") {
            val userId = testSetup.createTestUser(email = "multi-delete@test.com", realmId = testRealm)

            val tokens = (1..20).map { i ->
                createToken(userId = userId, tokenHash = "delete-token-$i").also {
                    tokenRepository.storeToken(it)
                }
            }

            withContext(Dispatchers.Default) {
                tokens.map { token ->
                    async { tokenRepository.deleteToken(token.tokenHash) }
                }.awaitAll()
            }

            tokens.forEach { token ->
                tokenRepository.findTokenByHash(token.tokenHash) shouldBe null
            }
        }
    }

    context("Mixed concurrent operations") {
        test("should handle concurrent revoke and find operations") {
            val userId = testSetup.createTestUser(email = "mixed-ops@test.com", realmId = testRealm)

            val tokenHash = "mixed-ops-token"
            val token = createToken(userId = userId, tokenHash = tokenHash)
            tokenRepository.storeToken(token)

            withContext(Dispatchers.Default) {
                val operations = (1..20).map { i ->
                    if (i % 2 == 0) {
                        async { tokenRepository.revokeToken(tokenHash) }
                    } else {
                        async { tokenRepository.findTokenByHash(tokenHash) }
                    }
                }
                operations.awaitAll()
            }

            val finalToken = tokenRepository.findTokenByHash(tokenHash)!!
            finalToken.revoked shouldBe true
        }

        test("should handle concurrent store and revoke operations for different tokens") {
            val userId = testSetup.createTestUser(email = "store-revoke@test.com", realmId = testRealm)

            val tokenFamily = UUID.randomUUID()

            withContext(Dispatchers.Default) {
                val operations = (1..50).map { i ->
                    when (i % 3) {
                        0 -> async {
                            val token = createToken(
                                userId = userId,
                                tokenHash = "concurrent-token-$i",
                                tokenFamily = tokenFamily
                            )
                            tokenRepository.storeToken(token)
                        }
                        1 -> async {
                            try {
                                tokenRepository.revokeToken("concurrent-token-${i - 1}")
                            } catch (_: Exception) {
                                // Token might not exist yet
                            }
                        }
                        else -> async {
                            tokenRepository.revokeTokenFamily(tokenFamily)
                        }
                    }
                }
                operations.awaitAll()
            }

            val allTokens = tokenRepository.findTokensByFamily(tokenFamily)
            allTokens.forEach { token ->
                token.userId shouldBe userId
                token.tokenFamily shouldBe tokenFamily
            }
        }
    }
})
