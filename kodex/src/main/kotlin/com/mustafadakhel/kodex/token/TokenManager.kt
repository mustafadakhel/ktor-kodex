package com.mustafadakhel.kodex.token

import com.auth0.jwt.interfaces.DecodedJWT
import com.mustafadakhel.kodex.model.TokenType
import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import java.util.*

internal interface TokenManager {
    suspend fun issueNewTokens(userId: UUID): TokenPair
    suspend fun refreshTokens(userId: UUID, refreshToken: String): TokenPair
    fun verifyToken(
        token: DecodedJWT,
        expectedType: TokenType,
    ): KodexPrincipal

    fun revokeTokensForUser(userId: UUID)
    fun revokeToken(token: String, delete: Boolean = true)
}

