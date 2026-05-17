@file:OptIn(InternalKodexApi::class)

package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.jdbc.InternalKodexApi
import com.mustafadakhel.kodex.jdbc.DatabaseDialect
import com.mustafadakhel.kodex.jdbc.and
import com.mustafadakhel.kodex.jdbc.eq
import com.mustafadakhel.kodex.model.JwtClaimsValidator
import com.mustafadakhel.kodex.model.JwtTokenVerifier
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.schema.CoreSchema
import com.mustafadakhel.kodex.schema.KodexDatabase
import com.mustafadakhel.kodex.service.saltedHashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.SecretsConfig
import com.mustafadakhel.kodex.util.now
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.datetime.TimeZone
import org.h2.jdbcx.JdbcDataSource
import java.util.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TokenRotationSecurityTest : FunSpec({

    lateinit var tokenManager: TokenManager
    lateinit var userRepository: UserRepository
    lateinit var tokenRepository: TokenRepository
    lateinit var db: KodexDatabase
    val realm = Realm("test-realm")
    val secret = "test-secret-key-32-bytes-long!!!"
    lateinit var testUserId: UUID

    fun createTokenManager(rotationPolicy: TokenRotationPolicy): TokenManager {
        val secretsConfig = SecretsConfig()
        with(RealmConfigScope(realm)) {
            secretsConfig.apply { this@with.raw(secret) }
        }

        val claimsConfig = ClaimsConfig().apply {
            issuer("test-issuer")
            audience("test-audience")
        }

        val jwtTokenIssuer = JwtTokenIssuer(
            secretsConfig = secretsConfig, claimsConfig = claimsConfig,
            userRepository = userRepository, realm = realm
        )

        val jwtTokenVerifier = JwtTokenVerifier(
            claimsValidator = JwtClaimsValidator(claimProvider = claimsConfig, realm = realm),
            timeZone = TimeZone.UTC,
            tokenPersistence = mapOf(TokenType.AccessToken to true, TokenType.RefreshToken to true),
            tokenRepository = tokenRepository, hashingService = saltedHashingService(),
            realm = realm
        )

        val signatureVerifier = JwtSignatureVerifier(secretsConfig)

        return DefaultTokenManager(
            jwtTokenIssuer = jwtTokenIssuer, jwtTokenVerifier = jwtTokenVerifier,
            signatureVerifier = signatureVerifier,
            tokenValidity = TokenValidity(access = 15.minutes, refresh = 7.hours),
            tokenRepository = tokenRepository, userRepository = userRepository,
            tokenPersistence = mapOf(TokenType.AccessToken to true, TokenType.RefreshToken to true),
            hashingService = saltedHashingService(), timeZone = TimeZone.UTC,
            realm = realm, tokenRotationPolicy = rotationPolicy,
            eventBus = mockk(relaxed = true)
        )
    }

    beforeEach {
        val ds = JdbcDataSource().apply {
            setUrl("jdbc:h2:mem:rotation_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        val core = CoreSchema("test_")
        db = KodexDatabase(ds, DatabaseDialect.H2, core)
        db.createSchema()

        userRepository = databaseUserRepository(db, realm.name)
        tokenRepository = databaseTokenRepository(db, realm.name)

        db.transaction {
            userRepository.seedRoles(listOf(Role(realm.name, "Test realm")))
            val result = userRepository.create(
                email = "test@example.com", phone = null, hashedPassword = "hashed",
                roleNames = listOf(realm.name), customAttributes = null, profile = null,
                currentTime = now(TimeZone.UTC)
            )
            val userDao = (result as UserRepository.CreateUserResult.Success).user
            testUserId = userDao.id
        }

        tokenManager = createTokenManager(TokenRotationPolicy.balanced())
    }

    context("Replay Attack Detection") {
        test("should reject reuse of consumed token with strict policy") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            tokenPair2 shouldNotBe tokenPair1

            // Old token is atomically revoked on rotation — reuse is rejected
            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should reject replay in middle of refresh chain") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            tokenManager.refreshTokens(testUserId, tokenPair2.refresh)

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair2.refresh)
            }
        }

        test("should reject reuse even with unsafe policy after atomic revocation") {
            tokenManager = createTokenManager(TokenRotationPolicy.unsafe())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            // With atomic revocation, old token is revoked regardless of policy
            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should reject reuse after any delay") {
            val customPolicy = TokenRotationPolicy(
                enabled = true, gracePeriod = 100.milliseconds,
                detectReplayAttacks = true, revokeOnReplay = true
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            Thread.sleep(150)

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }
    }

    context("Token Family Revocation") {
        test("should revoke old token on rotation") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            // After rotation, the old refresh token should be revoked
            val tokens = db.core.tokens
            val oldTokenRevoked = db.transaction {
                select(tokens)
                    .where { (tokens.userId eq testUserId) and (tokens.revoked eq true) }
                    .map { it[tokens.revoked] }
            }

            oldTokenRevoked.isNotEmpty() shouldBe true
        }

        test("should isolate token families from different sessions") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val session1TokenPair1 = tokenManager.issueNewTokens(testUserId)
            val session2TokenPair1 = tokenManager.issueNewTokens(testUserId)

            tokenManager.refreshTokens(testUserId, session1TokenPair1.refresh)

            // Old session1 token is revoked, but session2 token is unaffected
            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, session1TokenPair1.refresh)
            }

            shouldNotThrow<Exception> {
                tokenManager.refreshTokens(testUserId, session2TokenPair1.refresh)
            }
        }
    }

    context("Grace Period Edge Cases") {
        test("should reject reuse even within grace period since token is atomically revoked") {
            val customPolicy = TokenRotationPolicy(
                enabled = true, gracePeriod = 2.seconds,
                detectReplayAttacks = true, revokeOnReplay = true
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            // Token is atomically revoked on rotation — grace period no longer allows reuse
            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should handle zero grace period correctly - reuse rejected") {
            val customPolicy = TokenRotationPolicy(
                enabled = true, gracePeriod = 0.milliseconds,
                detectReplayAttacks = true, revokeOnReplay = true
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should reject reuse even with very long grace period since token is atomically revoked") {
            val customPolicy = TokenRotationPolicy(
                enabled = true, gracePeriod = 1.hours,
                detectReplayAttacks = true, revokeOnReplay = false
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            // Token is atomically revoked — grace period no longer permits reuse
            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }
    }

    context("Rotation Policy Variants") {
        test("disabled policy should still delete tokens after refresh") {
            tokenManager = createTokenManager(TokenRotationPolicy.disabled())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            tokenPair2 shouldNotBe tokenPair1

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("strict policy should reject any reuse") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("balanced policy should reject reuse since token is atomically revoked") {
            tokenManager = createTokenManager(TokenRotationPolicy.balanced())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }
    }

    context("Token Metadata Tracking") {
        test("should track firstUsedAt timestamp") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair = tokenManager.issueNewTokens(testUserId)

            Thread.sleep(50)

            tokenManager.refreshTokens(testUserId, tokenPair.refresh)

            val decodedToken = JWT.decode(tokenPair.refresh)
            val tokenId = UUID.fromString(decodedToken.id)

            val tokens = db.core.tokens
            val firstUsedAt = db.transaction {
                select(tokens)
                    .where { tokens.id eq tokenId }
                    .firstOrNull { it[tokens.firstUsedAt] }
            }

            firstUsedAt shouldNotBe null
        }

        test("should maintain token family across refreshes") {
            tokenManager = createTokenManager(TokenRotationPolicy.balanced())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            val tokenPair3 = tokenManager.refreshTokens(testUserId, tokenPair2.refresh)

            val decoded1 = JWT.decode(tokenPair1.refresh)
            val decoded2 = JWT.decode(tokenPair2.refresh)
            val decoded3 = JWT.decode(tokenPair3.refresh)

            val token1Id = UUID.fromString(decoded1.id)
            val token2Id = UUID.fromString(decoded2.id)
            val token3Id = UUID.fromString(decoded3.id)

            val tokens = db.core.tokens
            val families = db.transaction {
                listOf(token1Id, token2Id, token3Id).mapNotNull { id ->
                    select(tokens)
                        .where { tokens.id eq id }
                        .firstOrNull { it[tokens.tokenFamily] }
                }
            }.distinct()

            families.size shouldBe 1
        }

        test("should track parent-child relationship") {
            tokenManager = createTokenManager(TokenRotationPolicy.balanced())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            val decoded1 = JWT.decode(tokenPair1.refresh)
            val decoded2 = JWT.decode(tokenPair2.refresh)

            val token1Id = UUID.fromString(decoded1.id)
            val token2Id = UUID.fromString(decoded2.id)

            val tokens = db.core.tokens
            val parentTokenId = db.transaction {
                select(tokens)
                    .where { tokens.id eq token2Id }
                    .firstOrNull { it[tokens.parentTokenId] }
            }

            parentTokenId shouldBe token1Id
        }
    }

    context("Security Edge Cases") {
        test("should prevent token reuse across different users") {
            val user2Id = db.transaction {
                val result = userRepository.create(
                    email = "user2@example.com", phone = null, hashedPassword = "hashed",
                    roleNames = listOf(realm.name), customAttributes = null, profile = null,
                    currentTime = now(TimeZone.UTC)
                )
                (result as UserRepository.CreateUserResult.Success).user.id
            }

            val user1TokenPair = tokenManager.issueNewTokens(testUserId)

            shouldThrow<KodexThrowable.Authorization> {
                tokenManager.refreshTokens(user2Id, user1TokenPair.refresh)
            }
        }

        test("should allow exactly one of two sequential refresh attempts") {
            tokenManager = createTokenManager(TokenRotationPolicy.balanced())

            val tokenPair = tokenManager.issueNewTokens(testUserId)

            val result1 = runCatching { tokenManager.refreshTokens(testUserId, tokenPair.refresh) }
            val result2 = runCatching { tokenManager.refreshTokens(testUserId, tokenPair.refresh) }

            // Atomic consumption means exactly one succeeds
            result1.isSuccess shouldBe true
            result2.isSuccess shouldBe false
        }

        test("should reject access tokens for refresh") {
            tokenManager = createTokenManager(TokenRotationPolicy.balanced())

            val tokenPair = tokenManager.issueNewTokens(testUserId)

            shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
                tokenManager.refreshTokens(testUserId, tokenPair.access)
            }
        }
    }
})
