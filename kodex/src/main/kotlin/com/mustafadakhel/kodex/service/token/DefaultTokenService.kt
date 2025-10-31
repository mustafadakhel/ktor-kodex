package com.mustafadakhel.kodex.service.token

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.TokenEvent
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import java.util.UUID

/**
 * Default implementation of TokenService that delegates to TokenManager.
 *
 * This is a simple facade over the existing TokenManager, providing a clean
 * service layer API for token operations.
 */
internal class DefaultTokenService(
    private val tokenManager: TokenManager,
    private val eventBus: EventBus,
    private val realm: Realm
) : TokenService {

    override suspend fun issue(userId: UUID): TokenPair {
        val result = tokenManager.issueNewTokens(userId)

        val accessTokenId = extractTokenId(result.access)

        eventBus.publish(
            TokenEvent.Issued(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realm.owner,
                userId = userId,
                tokenId = accessTokenId
            )
        )

        return result
    }

    override suspend fun refresh(userId: UUID, refreshToken: String): TokenPair {
        return try {
            val oldTokenId = extractTokenId(refreshToken)
            val result = tokenManager.refreshTokens(userId, refreshToken)
            val newTokenId = extractTokenId(result.access)

            eventBus.publish(
                TokenEvent.Refreshed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm.owner,
                    userId = userId,
                    oldTokenId = oldTokenId,
                    newTokenId = newTokenId
                )
            )

            result
        } catch (e: Exception) {
            eventBus.publish(
                TokenEvent.RefreshFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm.owner,
                    userId = userId,
                    reason = e.message ?: "Unknown error"
                )
            )
            throw e
        }
    }

    override suspend fun revoke(userId: UUID) {
        tokenManager.revokeTokensForUser(userId)

        eventBus.publish(
            TokenEvent.Revoked(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realm.owner,
                userId = userId,
                revokedCount = -1
            )
        )
    }

    override suspend fun revokeToken(token: String, delete: Boolean) {
        val tokenId = extractTokenId(token)
        tokenManager.revokeToken(token, delete)

        eventBus.publish(
            TokenEvent.Revoked(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realm.owner,
                userId = UUID(0, 0),
                revokedCount = 1,
                tokenIds = listOf(tokenId)
            )
        )
    }

    override suspend fun verify(token: String): KodexPrincipal? {
        return runCatching {
            val jwt = JWT.decode(token)
            tokenManager.verifyToken(jwt, TokenType.AccessToken)
        }.getOrElse { exception ->
            eventBus.publish(
                TokenEvent.VerifyFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm.owner,
                    reason = exception.message ?: "Unknown error"
                )
            )
            null
        }
    }

    private fun extractTokenId(token: String): UUID {
        return try {
            val jwt = JWT.decode(token)
            UUID.fromString(jwt.id)
        } catch (e: Exception) {
            UUID(0, 0)
        }
    }
}
