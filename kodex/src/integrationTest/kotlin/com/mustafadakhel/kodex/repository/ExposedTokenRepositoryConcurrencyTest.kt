package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.model.database.Tokens
import com.mustafadakhel.kodex.model.database.UserDao
import com.mustafadakhel.kodex.model.database.Users
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.deleteAll
import java.util.*

/**
 * Concurrency tests for ExposedTokenRepository to verify atomic operations
 * and race condition handling.
 */
class ExposedTokenRepositoryConcurrencyTest : FunSpec({

    lateinit var tokenRepository: TokenRepository

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:token_concurrency_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            maximumPoolSize = 20  // Increased for concurrent operations
        }
        setupExposedEngine(HikariDataSource(config))
        tokenRepository = databaseTokenRepository()

        // Clear tokens and users tables
        exposedTransaction {
            Tokens.deleteAll()
            Users.deleteAll()
        }
    }

    /**
     * Creates a user in the database for foreign key constraints.
     */
    fun ensureUserExists(userId: UUID) {
        exposedTransaction {
            if (UserDao.findById(userId) == null) {
                val now = now(TimeZone.UTC)
                UserDao.new(userId) {
                    this.email = "test-${userId}@example.com"
                    this.phoneNumber = null
                    this.passwordHash = "hashed"
                    this.status = UserStatus.ACTIVE
                    this.createdAt = now
                    this.updatedAt = now
                    this.lastLoginAt = null
                    this.realmId = "test-realm"
                }
            }
        }
    }

    context("Concurrent revokeToken(tokenHash)") {
        test("should handle concurrent revocations atomically") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokenHash = "test-token-hash"
            val token = createToken(userId = userId, tokenHash = tokenHash)
            tokenRepository.storeToken(token)

            // Verify token is not revoked
            tokenRepository.findTokenByHash(tokenHash)!!.revoked shouldBe false

            // Execute 10 concurrent revocations of the same token
            withContext(Dispatchers.Default) {
                (1..10).map {
                    async {
                        tokenRepository.revokeToken(tokenHash)
                    }
                }.awaitAll()
            }

            // Verify token is revoked exactly once (no errors)
            val revokedToken = tokenRepository.findTokenByHash(tokenHash)!!
            revokedToken.revoked shouldBe true
        }

        test("should handle concurrent revocations of different tokens") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokens = (1..20).map { i ->
                createToken(
                    userId = userId,
                    tokenHash = "token-hash-$i"
                ).also { tokenRepository.storeToken(it) }
            }

            // Execute concurrent revocations
            withContext(Dispatchers.Default) {
                tokens.map { token ->
                    async {
                        tokenRepository.revokeToken(token.tokenHash)
                    }
                }.awaitAll()
            }

            // Verify all tokens are revoked
            tokens.forEach { token ->
                val revokedToken = tokenRepository.findTokenByHash(token.tokenHash)!!
                revokedToken.revoked shouldBe true
            }
        }
    }

    context("Concurrent revokeTokens(userId)") {
        test("should handle concurrent revocations by userId atomically") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokens = (1..50).map { i ->
                createToken(
                    userId = userId,
                    tokenHash = "user-token-$i"
                ).also { tokenRepository.storeToken(it) }
            }

            // Execute 5 concurrent calls to revokeTokens for the same user
            withContext(Dispatchers.Default) {
                (1..5).map {
                    async {
                        tokenRepository.revokeTokens(userId)
                    }
                }.awaitAll()
            }

            // Verify all tokens are revoked (no errors, no missed tokens)
            tokens.forEach { token ->
                val revokedToken = tokenRepository.findTokenByHash(token.tokenHash)!!
                revokedToken.revoked shouldBe true
            }
        }

        test("should handle concurrent revocations for different users") {
            val userIds = (1..10).map { UUID.randomUUID() }
            val tokensPerUser = 10

            // Ensure all users exist
            userIds.forEach { ensureUserExists(it) }

            // Create tokens for each user
            userIds.forEach { userId ->
                (1..tokensPerUser).forEach { i ->
                    val token = createToken(
                        userId = userId,
                        tokenHash = "user-${userId}-token-$i"
                    )
                    tokenRepository.storeToken(token)
                }
            }

            // Execute concurrent revocations for different users
            withContext(Dispatchers.Default) {
                userIds.map { userId ->
                    async {
                        tokenRepository.revokeTokens(userId)
                    }
                }.awaitAll()
            }

            // Verify all tokens for all users are revoked
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
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokens = (1..30).map { i ->
                createToken(
                    userId = userId,
                    tokenHash = "family-token-$i",
                    tokenFamily = tokenFamily
                ).also { tokenRepository.storeToken(it) }
            }

            // Execute 5 concurrent family revocations
            withContext(Dispatchers.Default) {
                (1..5).map {
                    async {
                        tokenRepository.revokeTokenFamily(tokenFamily)
                    }
                }.awaitAll()
            }

            // Verify all tokens in family are revoked
            tokens.forEach { token ->
                val revokedToken = tokenRepository.findTokenByHash(token.tokenHash)!!
                revokedToken.revoked shouldBe true
            }
        }

        test("should handle concurrent revocations of different families") {
            val families = (1..10).map { UUID.randomUUID() }
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokensPerFamily = 5

            // Create tokens for each family
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

            // Execute concurrent revocations for different families
            withContext(Dispatchers.Default) {
                families.map { familyId ->
                    async {
                        tokenRepository.revokeTokenFamily(familyId)
                    }
                }.awaitAll()
            }

            // Verify all tokens in all families are revoked
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
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokenHash = "delete-test-token"
            val token = createToken(userId = userId, tokenHash = tokenHash)
            tokenRepository.storeToken(token)

            // Verify token exists
            val storedToken = tokenRepository.findTokenByHash(tokenHash)
            storedToken?.tokenHash shouldBe tokenHash
            storedToken?.userId shouldBe userId

            // Execute 10 concurrent deletions of the same token
            withContext(Dispatchers.Default) {
                (1..10).map {
                    async {
                        tokenRepository.deleteToken(tokenHash)
                    }
                }.awaitAll()
            }

            // Verify token is deleted (no errors from concurrent deletes)
            tokenRepository.findTokenByHash(tokenHash) shouldBe null
        }

        test("should handle concurrent deletions of different tokens") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokens = (1..20).map { i ->
                createToken(
                    userId = userId,
                    tokenHash = "delete-token-$i"
                ).also { tokenRepository.storeToken(it) }
            }

            // Execute concurrent deletions
            withContext(Dispatchers.Default) {
                tokens.map { token ->
                    async {
                        tokenRepository.deleteToken(token.tokenHash)
                    }
                }.awaitAll()
            }

            // Verify all tokens are deleted
            tokens.forEach { token ->
                tokenRepository.findTokenByHash(token.tokenHash) shouldBe null
            }
        }
    }

    context("Mixed concurrent operations") {
        test("should handle concurrent revoke and find operations") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokenHash = "mixed-ops-token"
            val token = createToken(userId = userId, tokenHash = tokenHash)
            tokenRepository.storeToken(token)

            // Execute concurrent revoke and find operations
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

            // Token should be revoked
            val finalToken = tokenRepository.findTokenByHash(tokenHash)!!
            finalToken.revoked shouldBe true
        }

        test("should handle concurrent store and revoke operations for different tokens") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)

            val tokenFamily = UUID.randomUUID()

            withContext(Dispatchers.Default) {
                // Mix of store and revoke operations
                val operations = (1..50).map { i ->
                    when (i % 3) {
                        0 -> async {
                            // Store new token
                            val token = createToken(
                                userId = userId,
                                tokenHash = "concurrent-token-$i",
                                tokenFamily = tokenFamily
                            )
                            tokenRepository.storeToken(token)
                        }
                        1 -> async {
                            // Revoke by hash (if exists)
                            try {
                                tokenRepository.revokeToken("concurrent-token-${i - 1}")
                            } catch (e: Exception) {
                                // Token might not exist yet
                            }
                        }
                        else -> async {
                            // Revoke by family
                            tokenRepository.revokeTokenFamily(tokenFamily)
                        }
                    }
                }
                operations.awaitAll()
            }

            // Verify database is consistent (no crashes, no corruption)
            val allTokens = tokenRepository.findTokensByFamily(tokenFamily)
            allTokens.forEach { token ->
                // All tokens should either be revoked or not, but data should be valid
                token.userId shouldBe userId
                token.tokenFamily shouldBe tokenFamily
            }
        }
    }
})

private fun createToken(
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
        tokenFamily = tokenFamily,
        parentTokenId = null,
        firstUsedAt = null,
        lastUsedAt = null
    )
}
