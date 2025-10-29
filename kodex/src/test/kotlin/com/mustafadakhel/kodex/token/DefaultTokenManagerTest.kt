package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.model.*
import com.mustafadakhel.kodex.repository.TokenRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import java.util.*

class DefaultTokenManagerTest : StringSpec({
    val tokenValidity = TokenValidity.Default
    val timeZone = TimeZone.UTC
    val realm = Realm("test")

    fun createIssuer(accessId: UUID, refreshId: UUID, accessToken: String, refreshToken: String): TokenIssuer {
        val issuer = mockk<TokenIssuer>()
        coEvery {
            issuer.issue(
                any(),
                tokenValidity.access.inWholeMilliseconds,
                Claim.TokenType.AccessToken
            )
        } returns GeneratedToken(accessToken, accessId)
        coEvery {
            issuer.issue(
                any(),
                tokenValidity.refresh.inWholeMilliseconds,
                Claim.TokenType.RefreshToken
            )
        } returns GeneratedToken(refreshToken, refreshId)
        return issuer
    }

    "stores tokens when persistence flags are true" {
        runTest {
            val userId = UUID.randomUUID()
            val accessId = UUID.randomUUID()
            val refreshId = UUID.randomUUID()
            val issuer = createIssuer(accessId, refreshId, "access", "refresh")
            val repository = mockk<TokenRepository>(relaxed = true)
            val manager = DefaultTokenManager(
                jwtTokenIssuer = issuer,
                jwtTokenVerifier = mockk(relaxed = true),
                tokenValidity = tokenValidity,
                tokenRepository = repository,
                tokenPersistence = mapOf(TokenType.AccessToken to true, TokenType.RefreshToken to true),
                hashingService = mockk(relaxed = true),
                timeZone = timeZone,
                realm = realm
            )

            manager.issueNewTokens(userId)

            verify(exactly = 1) { repository.storeToken(match { it.id == accessId && it.type == TokenType.AccessToken }) }
            verify(exactly = 1) { repository.storeToken(match { it.id == refreshId && it.type == TokenType.RefreshToken }) }
        }
    }

    "skips storing tokens when persistence flags are false" {
        runTest {
            val userId = UUID.randomUUID()
            val issuer = createIssuer(UUID.randomUUID(), UUID.randomUUID(), "access", "refresh")
            val repository = mockk<TokenRepository>(relaxed = true)
            val manager = DefaultTokenManager(
                jwtTokenIssuer = issuer,
                jwtTokenVerifier = mockk(relaxed = true),
                tokenValidity = tokenValidity,
                tokenRepository = repository,
                tokenPersistence = mapOf(TokenType.AccessToken to false, TokenType.RefreshToken to false),
                hashingService = mockk(relaxed = true),
                timeZone = timeZone,
                realm = realm
            )

            manager.issueNewTokens(userId)

            verify(exactly = 0) { repository.storeToken(any()) }
        }
    }

    "refreshTokens deletes old refresh token and returns new pair" {
        runTest {
            val userId = UUID.randomUUID()
            val oldRefreshId = UUID.randomUUID()
            val newAccessId = UUID.randomUUID()
            val newRefreshId = UUID.randomUUID()
            val issuer = createIssuer(newAccessId, newRefreshId, "newA", "newR")
            val repository = mockk<TokenRepository>(relaxed = true)
            val verifier = mockk<TokenVerifier> {
                coEvery { verify(any(), TokenType.RefreshToken) } returns VerifiedToken(
                    userId = userId,
                    tokenId = oldRefreshId,
                    type = TokenType.RefreshToken,
                    roles = listOf(Role("role", "desc")),
                    claims = listOf(
                        Claim.JwtId(oldRefreshId.toString()),
                        Claim.Subject(userId.toString()),
                        Claim.TokenType.RefreshToken,
                        Claim.Roles(listOf("role")),
                        Claim.Realm(realm.owner),
                    )
                )
            }
            val manager = DefaultTokenManager(
                jwtTokenIssuer = issuer,
                jwtTokenVerifier = verifier,
                tokenValidity = tokenValidity,
                tokenRepository = repository,
                tokenPersistence = mapOf(TokenType.AccessToken to false, TokenType.RefreshToken to false),
                hashingService = mockk(relaxed = true),
                timeZone = timeZone,
                realm = realm
            )

            val refreshToken = JWT.create()
                .withJWTId(oldRefreshId.toString())
                .withSubject(userId.toString())
                .withClaim(Claim.TokenType.Key, "Refresh")
                .withArrayClaim(Claim.Roles.Key, arrayOf("role"))
                .withClaim(Claim.Realm.Key, realm.owner)
                .sign(Algorithm.HMAC256("secret"))

            val pair = manager.refreshTokens(userId, refreshToken)

            verify { repository.deleteToken(oldRefreshId) }
            pair shouldBe TokenPair("newA", "newR")
        }
    }

    "verifyToken returns principal on valid token" {
        val userId = UUID.randomUUID()
        val tokenId = UUID.randomUUID()
        val repository = mockk<TokenRepository>(relaxed = true)
        val verifier = mockk<TokenVerifier> {
            coEvery { verify(any(), TokenType.AccessToken) } returns VerifiedToken(
                userId = userId,
                tokenId = tokenId,
                type = TokenType.AccessToken,
                roles = listOf(Role("admin", "desc")),
                claims = listOf(
                    Claim.JwtId(tokenId.toString()),
                    Claim.Subject(userId.toString()),
                    Claim.TokenType.AccessToken,
                    Claim.Roles(listOf("admin")),
                    Claim.Realm(realm.owner),
                )
            )
        }
        val manager = DefaultTokenManager(
            jwtTokenIssuer = mockk(relaxed = true),
            jwtTokenVerifier = verifier,
            tokenValidity = tokenValidity,
            tokenRepository = repository,
            tokenPersistence = mapOf(TokenType.AccessToken to false, TokenType.RefreshToken to false),
            hashingService = mockk(relaxed = true),
            timeZone = timeZone,
            realm = realm
        )

        val token = JWT.create()
            .withJWTId(tokenId.toString())
            .withSubject(userId.toString())
            .withClaim(Claim.TokenType.Key, "Access")
            .withArrayClaim(Claim.Roles.Key, arrayOf("admin"))
            .withClaim(Claim.Realm.Key, realm.owner)
            .sign(Algorithm.HMAC256("secret"))

        val decoded = JWT.decode(token)
        val principal = manager.verifyToken(decoded, TokenType.AccessToken)

        verify { verifier.verify(any(), TokenType.AccessToken) }
        principal.userId shouldBe userId
        principal.type shouldBe TokenType.AccessToken
        principal.realm shouldBe realm
        principal.roles shouldBe listOf(Role("admin", "desc"))
    }
})
