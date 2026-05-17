package com.mustafadakhel.kodex.token

import com.auth0.jwt.interfaces.DecodedJWT
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.SecurityEvent
import com.mustafadakhel.kodex.tokens.ExpirationCalculator
import com.mustafadakhel.kodex.model.Claim
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.model.TokenValidity
import com.mustafadakhel.kodex.model.database.PersistedToken
import com.mustafadakhel.kodex.repository.TokenRepository
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.routes.auth.DefaultKodexPrincipal
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.toUuidOrNull
import com.mustafadakhel.kodex.util.tokenId
import io.ktor.server.auth.jwt.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.*
import kotlin.time.Duration

internal class DefaultTokenManager(
    private val jwtTokenIssuer: TokenIssuer,
    private val jwtTokenVerifier: TokenVerifier,
    private val signatureVerifier: JwtSignatureVerifier,
    private val tokenValidity: TokenValidity,
    private val tokenRepository: TokenRepository,
    private val userRepository: UserRepository,
    private val tokenPersistence: Map<TokenType, Boolean>,
    private val hashingService: HashingService,
    private val timeZone: TimeZone,
    private val realm: Realm,
    private val tokenRotationPolicy: TokenRotationPolicy,
    private val eventBus: EventBus
) : TokenManager {
    override suspend fun issueNewTokens(userId: UUID, additionalClaims: Map<String, Any>): TokenPair {
        return issueNewTokensWithFamily(userId, additionalClaims).tokenPair
    }

    override suspend fun issueNewTokensWithFamily(userId: UUID, additionalClaims: Map<String, Any>): TokenPairWithFamily {
        val roles = userRepository.findRoles(userId).map { it.name }
        val tokenFamily = UUID.randomUUID()
        val accessToken = issueToken(
            userId = userId,
            validityMs = tokenValidity.access,
            tokenType = TokenType.AccessToken,
            tokenFamily = tokenFamily,
            roles = roles,
            additionalClaims = additionalClaims
        )
        val refreshToken = issueToken(
            userId = userId,
            validityMs = tokenValidity.refresh,
            tokenType = TokenType.RefreshToken,
            tokenFamily = tokenFamily,
            roles = roles,
            additionalClaims = additionalClaims
        )
        return TokenPairWithFamily(
            tokenPair = TokenPair(accessToken.token, refreshToken.token),
            tokenFamily = tokenFamily
        )
    }

    private suspend fun issueToken(
        userId: UUID,
        validityMs: Duration,
        tokenType: TokenType,
        tokenFamily: UUID? = null,
        parentTokenId: UUID? = null,
        roles: List<String>? = null,
        additionalClaims: Map<String, Any> = emptyMap()
    ): GeneratedToken {
        val clockNow = CurrentKotlinInstant
        val token = jwtTokenIssuer.issue(
            userId = userId,
            validityMs = validityMs.inWholeMilliseconds,
            tokenType = tokenType.claim,
            roles = roles,
            tokenFamily = tokenFamily,
            additionalClaims = additionalClaims
        )
        if (tokenPersistence[tokenType] == true)
            tokenRepository.storeToken(
                PersistedToken(
                    id = token.tokenId,
                    userId = userId,
                    tokenHash = hashingService.hash(token.token),
                    type = tokenType,
                    revoked = false,
                    createdAt = clockNow.toLocalDateTime(TimeZone.UTC),
                    expiresAt = ExpirationCalculator.calculateExpiration(validityMs, TimeZone.UTC, clockNow),
                    realmId = realm.name,
                    tokenFamily = tokenFamily,
                    parentTokenId = parentTokenId,
                    firstUsedAt = null,
                    lastUsedAt = null
                )
            )
        return token
    }

    override suspend fun refreshTokens(userId: UUID, refreshToken: String): TokenPair {
        return refreshTokensWithFamily(userId, refreshToken).tokenPair
    }

    override suspend fun refreshTokensWithFamily(userId: UUID, refreshToken: String): TokenPairWithFamily {
        return if (tokenRotationPolicy.enabled) {
            refreshWithRotationWithFamily(userId, refreshToken)
        } else {
            refreshWithoutRotationWithFamily(userId, refreshToken)
        }
    }

    private suspend fun refreshWithoutRotationWithFamily(userId: UUID, refreshToken: String): TokenPairWithFamily {
        val decodedJWT = signatureVerifier.verify(refreshToken)
        verifyToken(decodedJWT, TokenType.RefreshToken)
        val credential = JWTCredential(decodedJWT)
        val tokenId = credential.tokenId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Refresh token does not contain a valid token ID")

        tokenRepository.consumeAndDeleteToken(tokenId, userId)
            ?: throw KodexThrowable.Authorization.InvalidToken("Token not found or already consumed")

        return issueNewTokensWithFamily(userId)
    }

    private suspend fun refreshWithRotationWithFamily(userId: UUID, refreshToken: String): TokenPairWithFamily {
        val clockNow = CurrentKotlinInstant
        val now = clockNow.toLocalDateTime(TimeZone.UTC)

        val decodedJWT = signatureVerifier.verify(refreshToken)
        verifyToken(decodedJWT, TokenType.RefreshToken)

        val credential = JWTCredential(decodedJWT)
        val tokenId = credential.tokenId
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Refresh token does not contain a valid token ID")

        val consumedToken = tokenRepository.consumeAndRevokeToken(tokenId, userId, now)

        if (consumedToken == null) {
            // Token was already consumed by a concurrent request — detect replay
            val existingToken = tokenRepository.findToken(tokenId)
            if (existingToken != null && existingToken.firstUsedAt != null && tokenRotationPolicy.detectReplayAttacks) {
                val gracePeriodEnd = existingToken.firstUsedAt.toInstant(TimeZone.UTC) + tokenRotationPolicy.gracePeriod
                val withinGracePeriod = clockNow < gracePeriodEnd

                if (!withinGracePeriod) {
                    val tokenFamily = existingToken.tokenFamily ?: existingToken.id

                    if (tokenRotationPolicy.revokeOnReplay) {
                        tokenRepository.revokeTokenFamily(tokenFamily)
                    }

                    eventBus.publish(
                        SecurityEvent.TokenReplayDetected(
                            eventId = UUID.randomUUID(),
                            timestamp = clockNow,
                            realmId = realm.name,
                            userId = userId,
                            tokenId = tokenId,
                            tokenFamily = tokenFamily,
                            firstUsedAt = existingToken.firstUsedAt.toString(),
                            gracePeriodEnd = gracePeriodEnd.toString()
                        )
                    )

                    throw KodexThrowable.Authorization.TokenReplayDetected(
                        tokenFamily = tokenFamily,
                        originalTokenId = tokenId
                    )
                }
            }

            throw KodexThrowable.Authorization.InvalidToken("Token already consumed or not found")
        }

        val tokenFamily = consumedToken.tokenFamily ?: consumedToken.id
        val newAccessToken = issueToken(userId, tokenValidity.access, TokenType.AccessToken, tokenFamily, null)
        val newRefreshToken = issueToken(userId, tokenValidity.refresh, TokenType.RefreshToken, tokenFamily, tokenId)

        return TokenPairWithFamily(
            tokenPair = TokenPair(
                access = newAccessToken.token,
                refresh = newRefreshToken.token
            ),
            tokenFamily = tokenFamily
        )
    }

    override fun revokeToken(token: String, delete: Boolean) {
        val decodedJWT = signatureVerifier.verify(token)
        val tokenId = decodedJWT.id?.toUuidOrNull()
            ?: throw KodexThrowable.Authorization.SuspiciousToken("Token does not contain a valid token ID")
        if (delete) {
            tokenRepository.deleteToken(tokenId)
        } else {
            tokenRepository.revokeToken(tokenId)
        }
    }

    override fun verifyToken(
        token: DecodedJWT,
        expectedType: TokenType,
    ): KodexPrincipal {
        val userId = token.subject?.toUuidOrNull()
        val tokenId = token.id?.toUuidOrNull()
        val tokenFamily = token.getClaim("tokenFamily")?.asString()?.toUuidOrNull()
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
            tokenFamily = tokenFamily,
        )
    }

    override fun revokeTokensForUser(userId: UUID) {
        tokenRepository.revokeTokens(userId)
    }
}
