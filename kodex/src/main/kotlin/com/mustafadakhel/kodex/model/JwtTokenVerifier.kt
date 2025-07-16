package com.mustafadakhel.kodex.model

import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.token.DecodedToken
import com.mustafadakhel.kodex.token.TokenVerifier
import com.mustafadakhel.kodex.token.VerifiedToken
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import kotlinx.datetime.TimeZone
import java.time.Instant
import java.util.*

internal class JwtTokenVerifier(
    private val claimsValidator: ClaimsValidator,
    private val timeZone: TimeZone,
    private val hashingService: HashingService,
    private val tokenRepository: TokenRepository,
    private val tokenPersistence: Map<TokenType, Boolean>,
    private val userRepository: UserRepository,
) : TokenVerifier {
    override fun verify(
        decodedToken: DecodedToken,
        expectedType: TokenType,
    ): VerifiedToken {

        val token = decodedToken.token
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Token does not contain a valid token value")

        val userId = decodedToken.userId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Token does not contain a valid user ID")

        val tokenId = decodedToken.tokenId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Token does not contain a valid token ID")

        val decodedType = decodedToken.claims.filterIsInstance<Claim.TokenType>().firstOrNull()
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Token does not contain a valid type claim")

        val expectedRoles = userRepository.findRoles(userId).takeIf { it.isNotEmpty() }
            ?: throw KodexThrowable.Authorization.UserHasNoRoles

        val tokenType = TokenType.fromClaim(decodedType)


        if (tokenType != expectedType)
            throw KodexThrowable.Authorization.SuspiciousToken("Token type does not match expected type: $expectedType")

        val validClaims = claimsValidator.validate(
            claims = decodedToken.claims,
            expectedType = expectedType,
            expectedRoles = expectedRoles.map { it.name },
        )
        if (!validClaims) throw KodexThrowable.Authorization.SuspiciousToken("Token claims are not valid")

        if (tokenPersistence[expectedType] == true) {
            val persistedToken = tokenRepository.findToken(tokenId)
                ?: throw KodexThrowable.Authorization.SuspiciousToken("Token not found in storage")
            validatePersistedToken(
                storedToken = persistedToken,
                tokenValue = token,
                userId = userId
            )
        }

        return VerifiedToken(
            userId = userId,
            tokenId = tokenId,
            type = tokenType,
            roles = expectedRoles.map { Role(it.name, it.description) },
            claims = decodedToken.claims,
        )

    }

    private fun validatePersistedToken(
        storedToken: PersistedToken,
        tokenValue: String,
        userId: UUID,
    ) {
        val now = getCurrentLocalDateTime(timeZone)

        if (storedToken.userId != userId)
            throw KodexThrowable.Authorization.SuspiciousToken("Token user ID does not match expected user ID")

        if (storedToken.expiresAt < now)
            throw KodexThrowable.Authorization.SuspiciousToken("Token has expired")

        if (hashingService.verify(tokenValue, storedToken.tokenHash).not())
            throw KodexThrowable.Authorization.SuspiciousToken("Token hash does not match stored token hash")

        if (storedToken.revoked)
            throw KodexThrowable.Authorization.SuspiciousToken("Token has been revoked")
    }
}

internal fun Claim.ExpiresAt.toDateOrNull(): Date? {
    requireNotNull(value) { "ExpiresAt claim value cannot be null" }
    return if (value > 0) {
        val instant = runCatching {
            Instant.ofEpochSecond(value!!)
        }.getOrNull()
        instant?.let {
            Date.from(it)
        }
    } else null
}