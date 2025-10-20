package com.mustafadakhel.kodex.token

import com.auth0.jwt.JWT
import com.auth0.jwt.interfaces.DecodedJWT
import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.routes.auth.DefaultKodexPrincipal
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import com.mustafadakhel.kodex.util.toUuidOrNull
import com.mustafadakhel.kodex.util.tokenId
import io.ktor.server.auth.jwt.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.*
import kotlin.time.Duration

internal class DefaultTokenManager(
    private val jwtTokenIssuer: TokenIssuer,
    private val jwtTokenVerifier: TokenVerifier,
    private val tokenValidity: TokenValidity,
    private val tokenRepository: TokenRepository,
    private val tokenPersistence: Map<TokenType, Boolean>,
    private val hashingService: HashingService,
    private val timeZone: TimeZone,
    private val realm: Realm,
    private val tokenRotationPolicy: TokenRotationPolicy,
    private val auditService: com.mustafadakhel.kodex.audit.AuditService,
) : TokenManager {
    override suspend fun issueNewTokens(userId: UUID): TokenPair {
        val accessToken = issueToken(
            userId = userId,
            validityMs = tokenValidity.access,
            tokenType = TokenType.AccessToken
        )
        val refreshToken = issueToken(
            userId = userId,
            validityMs = tokenValidity.refresh,
            tokenType = TokenType.RefreshToken
        )
        return TokenPair(accessToken.token, refreshToken.token)
    }

    private suspend fun issueToken(
        userId: UUID,
        validityMs: Duration,
        tokenType: TokenType,
        tokenFamily: UUID? = null,
        parentTokenId: UUID? = null
    ): GeneratedToken {
        val token = jwtTokenIssuer.issue(
            userId = userId,
            validityMs = validityMs.inWholeMilliseconds,
            tokenType = tokenType.claim
        )
        if (tokenPersistence[tokenType] == true)
            tokenRepository.storeToken(
                PersistedToken(
                    id = token.tokenId,
                    userId = userId,
                    tokenHash = hashingService.hash(token.token),
                    type = tokenType,
                    revoked = false,
                    createdAt = getCurrentLocalDateTime(timeZone),
                    expiresAt = CurrentKotlinInstant
                        .plus(validityMs)
                        .toLocalDateTime(timeZone),
                    tokenFamily = tokenFamily,
                    parentTokenId = parentTokenId,
                    usedAt = null
                )
            )
        return token
    }

    override suspend fun refreshTokens(userId: UUID, refreshToken: String): TokenPair {
        return if (tokenRotationPolicy.enabled) {
            refreshWithRotation(userId, refreshToken)
        } else {
            refreshWithoutRotation(userId, refreshToken)
        }
    }

    private suspend fun refreshWithoutRotation(userId: UUID, refreshToken: String): TokenPair {
        val decodedJWT = JWT.decode(refreshToken)
        verifyToken(decodedJWT, TokenType.RefreshToken)
        val credential = JWTCredential(decodedJWT)
        val tokenId = credential.tokenId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Refresh token does not contain a valid token ID")
        tokenRepository.deleteToken(tokenId)
        return issueNewTokens(userId)
    }

    private suspend fun refreshWithRotation(userId: UUID, refreshToken: String): TokenPair {
        val clockNow = Clock.System.now()
        val now = clockNow.toLocalDateTime(timeZone)

        val decodedJWT = JWT.decode(refreshToken)
        verifyToken(decodedJWT, TokenType.RefreshToken)

        val credential = JWTCredential(decodedJWT)
        val tokenId = credential.tokenId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Refresh token does not contain a valid token ID")
        val tokenHash = hashingService.hash(refreshToken)

        val persistedToken = tokenRepository.findTokenByHash(tokenHash)
            ?: throw KodexThrowable.Authorization.InvalidToken("Token not found")

        if (persistedToken.usedAt != null) {
            val gracePeriodEnd = persistedToken.usedAt!!.toInstant(timeZone) + tokenRotationPolicy.gracePeriod
            val withinGracePeriod = clockNow < gracePeriodEnd

            if (!withinGracePeriod && tokenRotationPolicy.detectReplayAttacks) {
                val tokenFamily = persistedToken.tokenFamily ?: persistedToken.id

                if (tokenRotationPolicy.revokeOnReplay) {
                    tokenRepository.revokeTokenFamily(tokenFamily)
                }

                auditService.audit(realm.owner) {
                    event(com.mustafadakhel.kodex.audit.AuditEvents.SECURITY_VIOLATION)
                    actor(userId)
                    failure("Refresh token replay attack detected")
                    context {
                        "tokenId" to tokenId.toString()
                        "tokenFamily" to tokenFamily.toString()
                        "originalUsedAt" to persistedToken.usedAt.toString()
                    }
                }

                throw KodexThrowable.Authorization.TokenReplayDetected(
                    tokenFamily = tokenFamily,
                    originalTokenId = tokenId
                )
            }
        }

        tokenRepository.markTokenAsUsed(tokenId, now)

        val tokenFamily = persistedToken.tokenFamily ?: persistedToken.id
        val newAccessToken = issueToken(userId, tokenValidity.access, TokenType.AccessToken, null, null)
        val newRefreshToken = issueToken(userId, tokenValidity.refresh, TokenType.RefreshToken, tokenFamily, tokenId)

        return TokenPair(
            access = newAccessToken.token,
            refresh = newRefreshToken.token
        )
    }

    override fun revokeToken(token: String, delete: Boolean) {
        val hash = hashingService.hash(token)
        tokenRepository.revokeToken(hash)
        if (delete) {
            tokenRepository.deleteToken(hash)
        }
    }

    override fun verifyToken(
        token: DecodedJWT,
        expectedType: TokenType,
    ): KodexPrincipal {
        val userId = token.subject?.toUuidOrNull()
        val tokenId = token.id?.toUuidOrNull()
        val claims = token.claims.map { Claim.from(it.key, it.value) }

        val decodedToken = DecodedToken(
            userId = userId,
            tokenId = tokenId,
            token = token.token,
            claims = claims,
        )

        val verifiedToken = jwtTokenVerifier.verify(
            decodedToken = decodedToken,
            expectedType = expectedType,
        )

        return DefaultKodexPrincipal(
            token = decodedToken.token,
            userId = verifiedToken.userId,
            type = verifiedToken.type,
            realm = realm,
            roles = verifiedToken.roles,
        )
    }

    override fun revokeTokensForUser(userId: UUID) {
        tokenRepository.revokeTokens(userId)
    }
}
