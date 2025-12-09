package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import com.mustafadakhel.kodex.model.JwtClaimsValidator
import com.mustafadakhel.kodex.model.JwtTokenVerifier
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.repository.database.databaseTokenRepository
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.routes.auth.RealmConfigScope
import com.mustafadakhel.kodex.service.saltedHashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.ClaimsConfig
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.SecretsConfig
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.mockk
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import java.util.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DefaultTokenManagerTest : FunSpec({

    lateinit var tokenManager: TokenManager
    lateinit var userRepository: UserRepository
    lateinit var tokenRepository: TokenRepository
    val realm = Realm("test-realm")
    val secret = "test-secret-key-32-bytes-long!!!"
    lateinit var testUserId: UUID

    fun createTokenManager(rotationPolicy: TokenRotationPolicy): TokenManager {
        val secretsConfig = SecretsConfig()
        with(RealmConfigScope(realm)) {
            secretsConfig.apply {
                this@with.raw(secret)
            }
        }

        val claimsConfig = ClaimsConfig().apply {
            issuer("test-issuer")
            audience("test-audience")
        }

        val jwtTokenIssuer = JwtTokenIssuer(
            secretsConfig = secretsConfig,
            claimsConfig = claimsConfig,
            userRepository = userRepository,
            realm = realm
        )

        val jwtTokenVerifier = JwtTokenVerifier(
            claimsValidator = JwtClaimsValidator(
                claimProvider = claimsConfig,
                realm = realm
            ),
            timeZone = TimeZone.UTC,
            tokenPersistence = mapOf(
                TokenType.AccessToken to true,
                TokenType.RefreshToken to true
            ),
            tokenRepository = tokenRepository,
            hashingService = saltedHashingService(),
            userRepository = userRepository
        )

        return DefaultTokenManager(
            jwtTokenIssuer = jwtTokenIssuer,
            jwtTokenVerifier = jwtTokenVerifier,
            tokenValidity = TokenValidity(
                access = 15.minutes,
                refresh = 7.hours
            ),
            tokenRepository = tokenRepository,
            userRepository = userRepository,
            tokenPersistence = mapOf(
                TokenType.AccessToken to true,
                TokenType.RefreshToken to true
            ),
            hashingService = saltedHashingService(),
            timeZone = TimeZone.UTC,
            realm = realm,
            tokenRotationPolicy = rotationPolicy,
            eventBus = mockk(relaxed = true)
        )
    }

    beforeEach {
        // H2 + Exposed setup
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = false)

        // Create repositories
        userRepository = databaseUserRepository()
        tokenRepository = databaseTokenRepository()

        // Seed test role and create test user
        exposedTransaction {
            userRepository.seedRoles(listOf(Role(realm.owner, "Test realm")))
            val result = userRepository.create(
                email = "test@example.com",
                phone = null,
                hashedPassword = "hashed",
                roleNames = listOf(realm.owner),
                customAttributes = null,
                profile = null,
                currentTime = now(TimeZone.UTC),
                realmId = realm.name
            )
            val userDao = (result as com.mustafadakhel.kodex.repository.UserRepository.CreateUserResult.Success).user
            testUserId = userDao.id
        }

        // Create token manager with default rotation policy
        tokenManager = createTokenManager(TokenRotationPolicy.balanced())
    }

    afterEach {
        exposedTransaction {
            Tokens.deleteAll()
            UserRoles.deleteAll()
            UserCustomAttributes.deleteAll()
            UserProfiles.deleteAll()
            Users.deleteAll()
            Roles.deleteAll()
        }
        Db.clearEngine()
    }

    context("Token Generation") {
        test("should generate valid token pair") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            tokenPair.access.shouldNotBeEmpty()
            tokenPair.refresh.shouldNotBeEmpty()
            tokenPair.access shouldNotBe tokenPair.refresh

            // Verify tokens can be decoded
            val accessDecoded = JWT.decode(tokenPair.access)
            val refreshDecoded = JWT.decode(tokenPair.refresh)

            accessDecoded.subject shouldBe testUserId.toString()
            refreshDecoded.subject shouldBe testUserId.toString()
        }

        test("should persist tokens to database") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            // Check database for persisted tokens
            val tokens = exposedTransaction {
                TokenDao.find { Tokens.userId eq testUserId }.toList()
            }

            tokens.size shouldBe 2
            tokens.any { it.type == TokenType.AccessToken.name } shouldBe true
            tokens.any { it.type == TokenType.RefreshToken.name } shouldBe true
        }

        test("should include user roles in token claims") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            val decoded = JWT.decode(tokenPair.access)
            val roles = decoded.getClaim("roles").asList(String::class.java)

            roles shouldNotBe null
            roles.contains(realm.owner) shouldBe true
        }
    }

    context("Token Refresh Without Rotation") {
        test("should refresh tokens without rotation policy") {
            // Create token manager with rotation disabled
            tokenManager = createTokenManager(TokenRotationPolicy.disabled())

            val originalTokenPair = tokenManager.issueNewTokens(testUserId)
            val newTokenPair = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)

            newTokenPair.access.shouldNotBeEmpty()
            newTokenPair.refresh.shouldNotBeEmpty()
            newTokenPair.access shouldNotBe originalTokenPair.access
            newTokenPair.refresh shouldNotBe originalTokenPair.refresh
        }
    }

    context("Token Refresh With Rotation") {
        test("should refresh tokens with rotation policy") {
            val originalTokenPair = tokenManager.issueNewTokens(testUserId)

            // Wait a bit to ensure different timestamps
            Thread.sleep(100)

            val newTokenPair = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)

            newTokenPair.access.shouldNotBeEmpty()
            newTokenPair.refresh.shouldNotBeEmpty()
            newTokenPair.access shouldNotBe originalTokenPair.access
            newTokenPair.refresh shouldNotBe originalTokenPair.refresh
        }

        test("should detect token replay attack") {
            // Create token manager with strict rotation policy
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val originalTokenPair = tokenManager.issueNewTokens(testUserId)

            // Use refresh token once
            val newTokenPair = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            newTokenPair.shouldNotBeNull()

            // Try to reuse the same refresh token (replay attack)
            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            }
        }

        test("should allow refresh within grace period") {
            // Create token manager with lenient policy (10s grace period)
            tokenManager = createTokenManager(TokenRotationPolicy.lenient())

            val originalTokenPair = tokenManager.issueNewTokens(testUserId)

            // Use refresh token once
            val newTokenPair1 = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            newTokenPair1.shouldNotBeNull()

            // Reuse within grace period (should succeed)
            val newTokenPair2 = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            newTokenPair2.shouldNotBeNull()
        }

        test("should revoke token family on replay after grace period") {
            // Create token manager with strict policy (0s grace period, revoke on replay)
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)

            // Use refresh token to get tokenPair2
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            // Use tokenPair2 to get tokenPair3
            val tokenPair3 = tokenManager.refreshTokens(testUserId, tokenPair2.refresh)

            // Try to reuse tokenPair1 (replay attack after grace period)
            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }

            // Check that entire token family is revoked
            val tokens = exposedTransaction {
                TokenDao.find { Tokens.userId eq testUserId }.toList()
            }
            tokens.all { it.revoked } shouldBe true
        }
    }

    context("Token Verification") {
        test("should verify valid access token") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)
            val decoded = JWT.decode(tokenPair.access)

            val principal = tokenManager.verifyToken(decoded, TokenType.AccessToken)

            principal.shouldNotBeNull()
            principal.userId shouldBe testUserId
            principal.realm shouldBe realm
        }

        test("should reject revoked token") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            // Revoke the token
            tokenManager.revokeToken(tokenPair.access, delete = false)

            // Try to verify revoked token
            val decoded = JWT.decode(tokenPair.access)
            shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
                tokenManager.verifyToken(decoded, TokenType.AccessToken)
            }
        }
    }

    context("Token Revocation") {
        test("should revoke specific token") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            tokenManager.revokeToken(tokenPair.access, delete = false)

            // Check token is revoked in database
            val token = exposedTransaction {
                TokenDao.find { (Tokens.userId eq testUserId) and (Tokens.type eq TokenType.AccessToken.name) }
                    .firstOrNull()
            }
            token.shouldNotBeNull()
            token.revoked shouldBe true
        }

        test("should revoke all user tokens") {
            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.issueNewTokens(testUserId)

            tokenManager.revokeTokensForUser(testUserId)

            // Check all tokens are revoked
            val tokens = exposedTransaction {
                TokenDao.find { Tokens.userId eq testUserId }.toList()
            }
            tokens.size shouldBe 4 // 2 access + 2 refresh
            tokens.all { it.revoked } shouldBe true
        }

        test("should delete token when delete flag is true") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            tokenManager.revokeToken(tokenPair.access, delete = true)

            // Check token is deleted from database
            val token = exposedTransaction {
                TokenDao.find { (Tokens.userId eq testUserId) and (Tokens.type eq TokenType.AccessToken.name) }
                    .firstOrNull()
            }
            token.shouldBeNull()
        }
    }
})
