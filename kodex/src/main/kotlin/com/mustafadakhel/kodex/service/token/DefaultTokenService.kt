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
import kotlin.coroutines.cancellation.CancellationException

internal class DefaultTokenService(
    private val tokenManager: TokenManager,
    private val eventBus: EventBus,
    private val realm: Realm
) : TokenService {

    override suspend fun issue(userId: UUID, sourceIp: String?, userAgent: String?): TokenPair {
        val result = tokenManager.issueNewTokensWithFamily(userId)

        val accessTokenId = extractTokenId(result.tokenPair.access)

        eventBus.publish(
            TokenEvent.Issued(
                eventId = UUID.randomUUID(),
                timestamp = CurrentKotlinInstant,
                realmId = realm.name,
                userId = userId,
                tokenId = accessTokenId,
                tokenFamily = result.tokenFamily,
                sourceIp = sourceIp,
                userAgent = userAgent
            )
        )

        return result.tokenPair
    }

    override suspend fun refresh(userId: UUID, refreshToken: String, sourceIp: String?, userAgent: String?): TokenPair {
        val now = CurrentKotlinInstant
        return try {
            val oldTokenId = extractTokenId(refreshToken)
            val result = tokenManager.refreshTokensWithFamily(userId, refreshToken)
            val newTokenId = extractTokenId(result.tokenPair.access)

            eventBus.publish(
                TokenEvent.Refreshed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm.name,
                    userId = userId,
                    oldTokenId = oldTokenId,
                    newTokenId = newTokenId,
                    tokenFamily = result.tokenFamily,
                    sourceIp = sourceIp,
                    userAgent = userAgent
                )
            )

            result.tokenPair
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eventBus.publish(
                TokenEvent.RefreshFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = now,
                    realmId = realm.name,
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
                realmId = realm.name,
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
                realmId = realm.name,
                userId = UUID(0, 0),
                revokedCount = 1,
                tokenIds = listOf(tokenId)
            )
        )
    }

    override suspend fun verify(token: String): KodexPrincipal? {
        return try {
            val jwt = JWT.decode(token)
            tokenManager.verifyToken(jwt, TokenType.AccessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eventBus.publish(
                TokenEvent.VerifyFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = realm.name,
                    reason = e.message ?: "Unknown error"
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
