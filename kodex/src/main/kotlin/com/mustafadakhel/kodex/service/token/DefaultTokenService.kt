package com.mustafadakhel.kodex.service.token

import com.auth0.jwt.JWT
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

/**
 * Default implementation of TokenService that delegates to TokenManager.
 *
 * This is a simple facade over the existing TokenManager, providing a clean
 * service layer API for token operations.
 */
internal class DefaultTokenService(
    private val tokenManager: TokenManager
) : TokenService {

    override suspend fun issueTokens(userId: UUID): TokenPair {
        return tokenManager.issueNewTokens(userId)
    }

    override suspend fun refresh(userId: UUID, refreshToken: String): TokenPair {
        return tokenManager.refreshTokens(userId, refreshToken)
    }

    override fun revokeTokens(userId: UUID) {
        tokenManager.revokeTokensForUser(userId)
    }

    override fun revokeToken(token: String, delete: Boolean) {
        tokenManager.revokeToken(token, delete)
    }

    override fun verifyAccessToken(token: String): KodexPrincipal? {
        return runCatching {
            val jwt = JWT.decode(token)
            tokenManager.verifyToken(jwt, TokenType.AccessToken)
        }.getOrNull()
    }
}
