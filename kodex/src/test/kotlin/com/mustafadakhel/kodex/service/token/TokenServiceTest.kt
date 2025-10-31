package com.mustafadakhel.kodex.service.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import java.util.UUID

class TokenServiceTest : FunSpec({
    lateinit var tokenManager: TokenManager
    lateinit var eventBus: EventBus
    lateinit var realm: Realm
    lateinit var tokenService: TokenService

    beforeEach {
        tokenManager = mockk()
        eventBus = mockk(relaxed = true)
        realm = mockk()
        every { realm.owner } returns "test-realm"
        tokenService = DefaultTokenService(tokenManager, eventBus, realm)
    }

    test("issueTokens should delegate to TokenManager.issueNewTokens") {
        val userId = UUID.randomUUID()
        val expectedTokenPair = TokenPair(
            access = "access-token",
            refresh = "refresh-token"
        )

        coEvery { tokenManager.issueNewTokens(userId) } returns expectedTokenPair

        val result = tokenService.issue(userId)

        result shouldBe expectedTokenPair
        coVerify(exactly = 1) { tokenManager.issueNewTokens(userId) }
    }

    test("refresh should delegate to TokenManager.refreshTokens") {
        val userId = UUID.randomUUID()
        val refreshToken = "refresh-token"
        val expectedTokenPair = TokenPair(
            access = "new-access-token",
            refresh = "new-refresh-token"
        )

        coEvery { tokenManager.refreshTokens(userId, refreshToken) } returns expectedTokenPair

        val result = tokenService.refresh(userId, refreshToken)

        result shouldBe expectedTokenPair
        coVerify(exactly = 1) { tokenManager.refreshTokens(userId, refreshToken) }
    }

    test("revokeTokens should delegate to TokenManager.revokeTokensForUser") {
        val userId = UUID.randomUUID()

        every { tokenManager.revokeTokensForUser(userId) } returns Unit

        tokenService.revoke(userId)

        verify(exactly = 1) { tokenManager.revokeTokensForUser(userId) }
    }

    test("revokeToken with default delete should delegate to TokenManager.revokeToken") {
        val token = "some-token"

        every { tokenManager.revokeToken(token, true) } returns Unit

        tokenService.revokeToken(token)

        verify(exactly = 1) { tokenManager.revokeToken(token, true) }
    }

    test("revokeToken with explicit delete=false should delegate correctly") {
        val token = "some-token"

        every { tokenManager.revokeToken(token, false) } returns Unit

        tokenService.revokeToken(token, delete = false)

        verify(exactly = 1) { tokenManager.revokeToken(token, false) }
    }

    test("verifyAccessToken should decode JWT and verify via TokenManager") {
        val userId = UUID.randomUUID()
        val token = JWT.create()
            .withSubject(userId.toString())
            .withClaim("type", TokenType.AccessToken.name)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256("test-secret"))

        val expectedPrincipal = mockk<KodexPrincipal>()
        every { expectedPrincipal.userId } returns userId

        every { tokenManager.verifyToken(any(), TokenType.AccessToken) } returns expectedPrincipal

        val result = tokenService.verify(token)

        result shouldNotBe null
        result?.userId shouldBe userId
        verify(exactly = 1) { tokenManager.verifyToken(any(), TokenType.AccessToken) }
    }

    test("verifyAccessToken should return null when verification fails") {
        val token = JWT.create()
            .withSubject(UUID.randomUUID().toString())
            .sign(Algorithm.HMAC256("test-secret"))

        every { tokenManager.verifyToken(any(), TokenType.AccessToken) } throws RuntimeException("Invalid token")

        val result = tokenService.verify(token)

        result shouldBe null
    }

    test("verifyAccessToken should return null for malformed JWT") {
        val malformedToken = "not.a.valid.jwt"

        val result = tokenService.verify(malformedToken)

        result shouldBe null
    }
})
