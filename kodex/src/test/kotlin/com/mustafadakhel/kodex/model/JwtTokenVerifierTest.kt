package com.mustafadakhel.kodex.model

import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.SaltedHashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.token.DecodedToken
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.*
import kotlin.time.Duration.Companion.minutes

private class FakeHashingService : SaltedHashingService {
    override fun hash(value: String): String = "hashed:$value"
    override fun verify(value: String, hash: String): Boolean =
        hash(value) == hash
}

class JwtTokenVerifierTest : FunSpec({
    val tokenValue = "token"
    val tokenId = UUID.randomUUID()
    val userId = UUID.randomUUID()
    val timeZone = TimeZone.UTC
    val hashingService = FakeHashingService()
    val claimsValidator = mockk<ClaimsValidator>()
    val tokenRepository = mockk<TokenRepository> {
        every { findToken(tokenId) } returns PersistedToken(
            id = tokenId,
            userId = userId,
            tokenHash = hashingService.hash(tokenValue),
            type = TokenType.RefreshToken,
            createdAt = getCurrentLocalDateTime(timeZone),
            expiresAt = CurrentKotlinInstant.plus(3600.minutes).toLocalDateTime(timeZone),
            revoked = false
        )
    }
    val userRepository = mockk<UserRepository> {
        every { findRoles(any()) } returns listOf(
            RoleEntity("user", null),
            RoleEntity("admin", null)
        )
        every { findById(any()) } returns null
        every { findFullById(any()) } returns null
    }
    val tokenPersistence = mapOf(
        TokenType.AccessToken to true,
        TokenType.RefreshToken to true
    )
    every { claimsValidator.validate(any(), any(), any()) } returns true

    test("valid persisted token passes verification") {
        val decoded = DecodedToken(
            userId = userId,
            tokenId = tokenId,
            token = tokenValue,
            claims = listOf(
                Claim.TokenType.RefreshToken,
                Claim.Realm("test-realm"),
            ),
        )
        val verifier = JwtTokenVerifier(
            claimsValidator = claimsValidator,
            timeZone = timeZone,
            hashingService = hashingService,
            tokenRepository = tokenRepository,
            tokenPersistence = tokenPersistence,
            userRepository = userRepository
        )
        shouldNotThrowAny {
            verifier.verify(decoded, TokenType.RefreshToken)
        }
    }

    test("invalid persisted token throws SuspiciousTokenException") {
        val decoded = DecodedToken(
            userId = userId,
            tokenId = tokenId,
            token = tokenValue,
            claims = emptyList(),
        )
        val verifier = JwtTokenVerifier(
            claimsValidator = claimsValidator,
            timeZone = timeZone,
            hashingService = hashingService,
            tokenRepository = tokenRepository,
            tokenPersistence = tokenPersistence,
            userRepository = userRepository
        )
        shouldThrow<KodexThrowable.Authorization.SuspiciousToken> {
            verifier.verify(decoded, TokenType.RefreshToken)
        }
    }
})
