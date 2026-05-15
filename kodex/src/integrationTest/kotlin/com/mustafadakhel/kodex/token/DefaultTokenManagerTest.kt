package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.mockk
import kotlinx.datetime.TimeZone
import org.h2.jdbcx.JdbcDataSource
import java.util.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DefaultTokenManagerTest : FunSpec({

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
            userRepository = userRepository, realm = realm
        )

        return DefaultTokenManager(
            jwtTokenIssuer = jwtTokenIssuer, jwtTokenVerifier = jwtTokenVerifier,
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
            setUrl("jdbc:h2:mem:token_mgr_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
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

    context("Token Generation") {
        test("should generate valid token pair") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            tokenPair.access.shouldNotBeEmpty()
            tokenPair.refresh.shouldNotBeEmpty()
            tokenPair.access shouldNotBe tokenPair.refresh

            val accessDecoded = JWT.decode(tokenPair.access)
            val refreshDecoded = JWT.decode(tokenPair.refresh)

            accessDecoded.subject shouldBe testUserId.toString()
            refreshDecoded.subject shouldBe testUserId.toString()
        }

        test("should persist tokens to database") {
            tokenManager.issueNewTokens(testUserId)

            val tokens = db.core.tokens
            val tokenCount = db.transaction {
                select(tokens)
                    .where { tokens.userId eq testUserId }
                    .count()
            }

            tokenCount shouldBe 2
        }

        test("should include user roles in token claims") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)

            val decoded = JWT.decode(tokenPair.access)
            val roles = decoded.getClaim("roles").asList(String::class.java)

            roles shouldNotBe null
            roles.contains(realm.name) shouldBe true
        }
    }

    context("Token Refresh Without Rotation") {
        test("should refresh tokens without rotation policy") {
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

            Thread.sleep(100)

            val newTokenPair = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)

            newTokenPair.access.shouldNotBeEmpty()
            newTokenPair.refresh.shouldNotBeEmpty()
            newTokenPair.access shouldNotBe originalTokenPair.access
            newTokenPair.refresh shouldNotBe originalTokenPair.refresh
        }

        test("should detect token replay attack") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val originalTokenPair = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            }
        }

        test("should allow refresh within grace period") {
            tokenManager = createTokenManager(TokenRotationPolicy.unsafe())

            val originalTokenPair = tokenManager.issueNewTokens(testUserId)
            val newTokenPair1 = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            newTokenPair1.shouldNotBeNull()

            val newTokenPair2 = tokenManager.refreshTokens(testUserId, originalTokenPair.refresh)
            newTokenPair2.shouldNotBeNull()
        }

        test("should revoke token family on replay after grace period") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            tokenManager.refreshTokens(testUserId, tokenManager.refreshTokens(testUserId, tokenManager.issueNewTokens(testUserId).refresh).refresh)

            // Create a fresh chain and replay
            val freshPair = tokenManager.issueNewTokens(testUserId)
            val freshPair2 = tokenManager.refreshTokens(testUserId, freshPair.refresh)
            tokenManager.refreshTokens(testUserId, freshPair2.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, freshPair.refresh)
            }
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
            tokenManager.revokeToken(tokenPair.access, delete = false)

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

            val tokens = db.core.tokens
            val revokedCount = db.transaction {
                select(tokens)
                    .where {
                        (tokens.userId eq testUserId) and
                        (tokens.type eq TokenType.AccessToken.name) and
                        (tokens.revoked eq true)
                    }.count()
            }
            revokedCount shouldBe 1
        }

        test("should revoke all user tokens") {
            tokenManager.issueNewTokens(testUserId)
            tokenManager.issueNewTokens(testUserId)

            tokenManager.revokeTokensForUser(testUserId)

            val tokens = db.core.tokens
            val allTokens = db.transaction {
                select(tokens)
                    .columns(tokens.revoked)
                    .where { tokens.userId eq testUserId }
                    .map { it[tokens.revoked] }
            }
            allTokens.size shouldBe 4
            allTokens.all { it } shouldBe true
        }

        test("should delete token when delete flag is true") {
            val tokenPair = tokenManager.issueNewTokens(testUserId)
            tokenManager.revokeToken(tokenPair.access, delete = true)

            val tokens = db.core.tokens
            val accessTokenCount = db.transaction {
                select(tokens)
                    .where {
                        (tokens.userId eq testUserId) and
                        (tokens.type eq TokenType.AccessToken.name)
                    }.count()
            }
            accessTokenCount shouldBe 0
        }
    }
})
