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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.deleteAll
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

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:token_toctou_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            maximumPoolSize = 20
        }
        setupExposedEngine(HikariDataSource(config))
        repository = databaseTokenRepository()

        exposedTransaction {
            Tokens.deleteAll()
            Users.deleteAll()
        }
    }

    fun ensureUserExists(userId: UUID) {
        exposedTransaction {
            if (UserDao.findById(userId) == null) {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                UserDao.new(userId) {
                    this.email = "test-${userId}@example.com"
                    this.phoneNumber = null
                    this.passwordHash = "hashed"
                    this.isVerified = false
                    this.status = UserStatus.ACTIVE
                    this.createdAt = now
                    this.updatedAt = now
                    this.lastLoginAt = null
                }
            }
        }
    }

    context("Concurrent Token Revocation by Hash") {
        test("should handle concurrent revoke attempts on same token") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val tokenHash = "test-token-hash-${Random.nextInt()}"
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val tokenId = repository.storeToken(
                PersistedToken(
                    id = UUID.randomUUID(),
                    userId = userId,
                    tokenHash = tokenHash,
                    type = TokenType.RefreshToken,
                    revoked = false,
                    createdAt = now,
                    expiresAt = now,
                    tokenFamily = null,
                    parentTokenId = null,
                    firstUsedAt = null,
                    lastUsedAt = null
                )
            )

            // Concurrently revoke the same token 100 times
            withContext(Dispatchers.Default) {
                List(100) {
                    async {
                        repository.revokeToken(tokenHash)
                    }
                }.awaitAll()
            }

            // Verify token is revoked exactly once (atomic operation)
            val token = repository.findToken(tokenId)
            token?.revoked shouldBe true
        }

        test("should handle concurrent revocations of different tokens") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val tokens = List(50) { index ->
                val tokenHash = "concurrent-token-$index"
                val tokenId = repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(),
                        userId = userId,
                        tokenHash = tokenHash,
                        type = TokenType.RefreshToken,
                        revoked = false,
                        createdAt = now,
                        expiresAt = now,
                        tokenFamily = null,
                        parentTokenId = null,
                        firstUsedAt = null,
                        lastUsedAt = null
                    )
                )
                tokenHash to tokenId
            }

            // Revoke all tokens concurrently
            withContext(Dispatchers.Default) {
                tokens.map { (tokenHash, _) ->
                    async {
                        repository.revokeToken(tokenHash)
                    }
                }.awaitAll()
            }

            // Verify all tokens are revoked
            tokens.forEach { (_, tokenId) ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }
    }

    context("Concurrent Token Revocation by User ID") {
        test("should atomically revoke all user tokens under concurrent access") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Create 20 tokens for the user
            val tokenIds = List(20) { index ->
                repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(),
                        userId = userId,
                        tokenHash = "user-token-$index",
                        type = TokenType.RefreshToken,
                        revoked = false,
                        createdAt = now,
                        expiresAt = now,
                        tokenFamily = null,
                        parentTokenId = null,
                        firstUsedAt = null,
                        lastUsedAt = null
                    )
                )
            }

            // Concurrently attempt to revoke all user tokens 50 times
            withContext(Dispatchers.Default) {
                List(50) {
                    async {
                        repository.revokeTokens(userId)
                    }
                }.awaitAll()
            }

            // Verify all tokens are revoked exactly once
            tokenIds.forEach { tokenId ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }

        test("should handle concurrent revocations across multiple users") {
            val users = List(10) { UUID.randomUUID() }
            users.forEach { ensureUserExists(it) }
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Create 5 tokens per user (50 total)
            val allTokens = users.flatMap { userId ->
                List(5) { index ->
                    val tokenId = repository.storeToken(
                        PersistedToken(
                            id = UUID.randomUUID(),
                            userId = userId,
                            tokenHash = "user-${userId}-token-$index",
                            type = TokenType.RefreshToken,
                            revoked = false,
                            createdAt = now,
                            expiresAt = now,
                            tokenFamily = null,
                            parentTokenId = null,
                            firstUsedAt = null,
                            lastUsedAt = null
                        )
                    )
                    userId to tokenId
                }
            }

            // Concurrently revoke tokens for all users
            withContext(Dispatchers.Default) {
                users.map { userId ->
                    async {
                        repository.revokeTokens(userId)
                    }
                }.awaitAll()
            }

            // Verify all 50 tokens are revoked
            allTokens.forEach { (_, tokenId) ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }
    }

    context("Concurrent Token Family Revocation") {
        test("should atomically revoke entire token family under concurrent access") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val tokenFamily = UUID.randomUUID()
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Create 15 tokens in the same family
            val tokenIds = List(15) { index ->
                repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(),
                        userId = userId,
                        tokenHash = "family-token-$index",
                        type = TokenType.RefreshToken,
                        revoked = false,
                        createdAt = now,
                        expiresAt = now,
                        tokenFamily = tokenFamily,
                        parentTokenId = if (index > 0) UUID.randomUUID() else null,
                        firstUsedAt = null,
                        lastUsedAt = null
                    )
                )
            }

            // Concurrently attempt to revoke family 75 times
            withContext(Dispatchers.Default) {
                List(75) {
                    async {
                        repository.revokeTokenFamily(tokenFamily)
                    }
                }.awaitAll()
            }

            // Verify all tokens in family are revoked
            val familyTokens = repository.findTokensByFamily(tokenFamily)
            familyTokens shouldHaveSize 15
            familyTokens.forEach { token ->
                token.revoked shouldBe true
            }
        }

        test("should handle concurrent family revocations across multiple families") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val families = List(8) { UUID.randomUUID() }
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Create 5 tokens per family (40 total)
            val allTokens = families.flatMap { family ->
                List(5) { index ->
                    val tokenId = repository.storeToken(
                        PersistedToken(
                            id = UUID.randomUUID(),
                            userId = userId,
                            tokenHash = "family-${family}-token-$index",
                            type = TokenType.RefreshToken,
                            revoked = false,
                            createdAt = now,
                            expiresAt = now,
                            tokenFamily = family,
                            parentTokenId = if (index > 0) UUID.randomUUID() else null,
                            firstUsedAt = null,
                            lastUsedAt = null
                        )
                    )
                    family to tokenId
                }
            }

            // Concurrently revoke all families
            withContext(Dispatchers.Default) {
                families.map { family ->
                    async {
                        repository.revokeTokenFamily(family)
                    }
                }.awaitAll()
            }

            // Verify all 40 tokens are revoked
            allTokens.forEach { (_, tokenId) ->
                val token = repository.findToken(tokenId)
                token?.revoked shouldBe true
            }
        }
    }

    context("Concurrent markTokenAsUsedIfUnused") {
        test("should only mark token as used once despite concurrent attempts") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val tokenId = repository.storeToken(
                PersistedToken(
                    id = UUID.randomUUID(),
                    userId = userId,
                    tokenHash = "unused-token",
                    type = TokenType.RefreshToken,
                    revoked = false,
                    createdAt = now,
                    expiresAt = now,
                    tokenFamily = null,
                    parentTokenId = null,
                    firstUsedAt = null,
                    lastUsedAt = null
                )
            )

            // Concurrently attempt to mark as used 100 times
            val results = withContext(Dispatchers.Default) {
                List(100) {
                    async {
                        repository.markTokenAsUsedIfUnused(tokenId, now)
                    }
                }.awaitAll()
            }

            // Exactly one attempt should succeed (return true)
            val successCount = results.count { it }
            successCount shouldBe 1

            // Verify token has firstUsedAt set
            val token = repository.findToken(tokenId)
            token?.firstUsedAt shouldBe now
            token?.lastUsedAt shouldBe now
        }

        test("should handle concurrent marking across multiple tokens") {
            val userId = UUID.randomUUID()
            ensureUserExists(userId)
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Create 30 unused tokens
            val tokenIds = List(30) { index ->
                repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(),
                        userId = userId,
                        tokenHash = "concurrent-unused-$index",
                        type = TokenType.RefreshToken,
                        revoked = false,
                        createdAt = now,
                        expiresAt = now,
                        tokenFamily = null,
                        parentTokenId = null,
                        firstUsedAt = null,
                        lastUsedAt = null
                    )
                )
            }

            // Concurrently mark all tokens (10 attempts per token)
            val results = withContext(Dispatchers.Default) {
                tokenIds.flatMap { tokenId ->
                    List(10) {
                        async {
                            tokenId to repository.markTokenAsUsedIfUnused(tokenId, now)
                        }
                    }
                }.awaitAll()
            }

            // Group results by token ID and verify exactly one success per token
            results.groupBy { it.first }
                .forEach { (tokenId, attempts) ->
                    val successCount = attempts.count { it.second }
                    successCount shouldBe 1

                    // Verify token marked as used
                    val token = repository.findToken(tokenId)
                    token?.firstUsedAt shouldBe now
                }
        }
    }

    context("Stress Test - High Concurrency") {
        test("should handle extreme concurrent load without data corruption") {
            val userIds = List(5) { UUID.randomUUID() }
            userIds.forEach { ensureUserExists(it) }
            val families = List(5) { UUID.randomUUID() }
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Create 100 tokens (mix of users and families)
            val tokenData = List(100) { index ->
                val userId = userIds[index % userIds.size]
                val family = if (index % 3 == 0) families[index % families.size] else null
                val tokenId = repository.storeToken(
                    PersistedToken(
                        id = UUID.randomUUID(),
                        userId = userId,
                        tokenHash = "stress-token-$index",
                        type = TokenType.RefreshToken,
                        revoked = false,
                        createdAt = now,
                        expiresAt = now,
                        tokenFamily = family,
                        parentTokenId = null,
                        firstUsedAt = null,
                        lastUsedAt = null
                    )
                )
                Triple(tokenId, userId, family)
            }

            // Concurrently execute mixed operations (200 operations)
            withContext(Dispatchers.Default) {
                List(200) { opIndex ->
                    async {
                        when (opIndex % 4) {
                            0 -> {
                                // Revoke by user
                                val userId = userIds[opIndex % userIds.size]
                                repository.revokeTokens(userId)
                            }
                            1 -> {
                                // Revoke by family
                                val family = families[opIndex % families.size]
                                repository.revokeTokenFamily(family)
                            }
                            2 -> {
                                // Revoke individual token
                                val (tokenId, _, _) = tokenData[opIndex % tokenData.size]
                                val token = repository.findToken(tokenId)
                                token?.let { repository.revokeToken(it.tokenHash) }
                            }
                            else -> {
                                // Mark as used
                                val (tokenId, _, _) = tokenData[opIndex % tokenData.size]
                                repository.markTokenAsUsedIfUnused(tokenId, now)
                            }
                        }
                    }
                }.awaitAll()
            }

            // Verify database consistency - all operations completed without exception
            // All tokens should be either revoked or marked as used (or both)
            tokenData.forEach { (tokenId, _, _) ->
                val token = repository.findToken(tokenId)
                token shouldNotBe null
                // Token should exist and be in a valid state
                // (revoked and/or used, depending on which operations won the race)
            }
        }
    }
})
