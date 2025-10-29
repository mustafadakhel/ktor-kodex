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
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import java.util.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TokenRotationSecurityTest : FunSpec({

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
            extensions = ExtensionRegistry.empty()
        )
    }

    beforeEach {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = false)

        userRepository = databaseUserRepository()
        tokenRepository = databaseTokenRepository()

        exposedTransaction {
            userRepository.seedRoles(listOf(Role(realm.owner, "Test realm")))
            val result = userRepository.create(
                email = "test@example.com",
                phone = null,
                hashedPassword = "hashed",
                roleNames = listOf(realm.owner),
                customAttributes = null,
                profile = null,
                currentTime = getCurrentLocalDateTime(TimeZone.UTC)
            )
            val userDao = (result as com.mustafadakhel.kodex.repository.UserRepository.CreateUserResult.Success).user
            testUserId = userDao.id
        }

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

    context("Replay Attack Detection") {
        test("should detect immediate replay with strict policy") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            tokenPair2 shouldNotBe tokenPair1

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should detect replay in middle of refresh chain") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            val tokenPair3 = tokenManager.refreshTokens(testUserId, tokenPair2.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair2.refresh)
            }
        }

        test("should allow single use within grace period with lenient policy") {
            tokenManager = createTokenManager(TokenRotationPolicy.lenient())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            Thread.sleep(50)

            shouldNotThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should detect replay after grace period expires") {
            val customPolicy = TokenRotationPolicy(
                enabled = true,
                gracePeriod = 100.milliseconds,
                detectReplayAttacks = true,
                revokeOnReplay = true
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            Thread.sleep(150)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }
    }

    context("Token Family Revocation") {
        test("should revoke entire token family on replay attack") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            val tokenPair3 = tokenManager.refreshTokens(testUserId, tokenPair2.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }

            val allTokens = exposedTransaction {
                TokenDao.find { Tokens.userId eq testUserId }.toList()
            }

            allTokens.all { it.revoked } shouldBe true
        }

        test("should not revoke family if revokeOnReplay is false") {
            val customPolicy = TokenRotationPolicy(
                enabled = true,
                gracePeriod = 0.seconds,
                detectReplayAttacks = true,
                revokeOnReplay = false
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }

            val allTokens = exposedTransaction {
                TokenDao.find { Tokens.userId eq testUserId }.toList()
            }

            allTokens.any { !it.revoked } shouldBe true
        }

        test("should isolate token families from different sessions") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val session1TokenPair1 = tokenManager.issueNewTokens(testUserId)
            val session2TokenPair1 = tokenManager.issueNewTokens(testUserId)

            val session1TokenPair2 = tokenManager.refreshTokens(testUserId, session1TokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, session1TokenPair1.refresh)
            }

            shouldNotThrow<Exception> {
                tokenManager.refreshTokens(testUserId, session2TokenPair1.refresh)
            }
        }
    }

    context("Grace Period Edge Cases") {
        test("should allow reuse within grace period after first use") {
            val customPolicy = TokenRotationPolicy(
                enabled = true,
                gracePeriod = 2.seconds,
                detectReplayAttacks = true,
                revokeOnReplay = true
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            Thread.sleep(500)

            shouldNotThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should handle zero grace period correctly") {
            val customPolicy = TokenRotationPolicy(
                enabled = true,
                gracePeriod = 0.milliseconds,
                detectReplayAttacks = true,
                revokeOnReplay = true
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("should handle very long grace period") {
            val customPolicy = TokenRotationPolicy(
                enabled = true,
                gracePeriod = 1.hours,
                detectReplayAttacks = true,
                revokeOnReplay = false
            )
            tokenManager = createTokenManager(customPolicy)

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            Thread.sleep(100)

            shouldNotThrow<Exception> {
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

            shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("strict policy should have zero tolerance") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }

        test("balanced policy should allow brief grace period") {
            tokenManager = createTokenManager(TokenRotationPolicy.balanced())

            val tokenPair1 = tokenManager.issueNewTokens(testUserId)
            val tokenPair2 = tokenManager.refreshTokens(testUserId, tokenPair1.refresh)

            Thread.sleep(100)

            shouldNotThrow<Exception> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }

            Thread.sleep(5000)

            shouldThrow<KodexThrowable.Authorization.TokenReplayDetected> {
                tokenManager.refreshTokens(testUserId, tokenPair1.refresh)
            }
        }
    }

    context("Token Metadata Tracking") {
        test("should track firstUsedAt timestamp") {
            tokenManager = createTokenManager(TokenRotationPolicy.strict())

            val tokenPair = tokenManager.issueNewTokens(testUserId)
            val beforeRefresh = System.currentTimeMillis()

            Thread.sleep(50)

            tokenManager.refreshTokens(testUserId, tokenPair.refresh)

            val afterRefresh = System.currentTimeMillis()

            val decodedToken = JWT.decode(tokenPair.refresh)
            val tokenId = UUID.fromString(decodedToken.id)

            val token = exposedTransaction {
                TokenDao.findById(tokenId)
            }

            token?.firstUsedAt shouldNotBe null
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

            val tokens = exposedTransaction {
                listOf(
                    TokenDao.findById(token1Id),
                    TokenDao.findById(token2Id),
                    TokenDao.findById(token3Id)
                )
            }

            val families = tokens.mapNotNull { it?.tokenFamily }.distinct()
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

            val token2 = exposedTransaction {
                TokenDao.findById(token2Id)
            }

            token2?.parentTokenId shouldBe token1Id
        }
    }

    context("Security Edge Cases") {
        test("should prevent token reuse across different users") {
            val user2Id = exposedTransaction {
                val result = userRepository.create(
                    email = "user2@example.com",
                    phone = null,
                    hashedPassword = "hashed",
                    roleNames = listOf(realm.owner),
                    customAttributes = null,
                    profile = null,
                    currentTime = getCurrentLocalDateTime(TimeZone.UTC)
                )
                (result as com.mustafadakhel.kodex.repository.UserRepository.CreateUserResult.Success).user.id
            }

            val user1TokenPair = tokenManager.issueNewTokens(testUserId)

            shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
                tokenManager.refreshTokens(user2Id, user1TokenPair.refresh)
            }
        }

        test("should handle concurrent refresh attempts gracefully") {
            tokenManager = createTokenManager(TokenRotationPolicy.lenient())

            val tokenPair = tokenManager.issueNewTokens(testUserId)

            val result1 = runCatching {
                tokenManager.refreshTokens(testUserId, tokenPair.refresh)
            }

            val result2 = runCatching {
                tokenManager.refreshTokens(testUserId, tokenPair.refresh)
            }

            result1.isSuccess shouldBe true
            result2.isSuccess shouldBe true
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
