package com.mustafadakhel.kodex.service.token

import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

public interface TokenService {
    public suspend fun issueTokens(userId: UUID): TokenPair
    public suspend fun refresh(userId: UUID, refreshToken: String): TokenPair
    public suspend fun revokeTokens(userId: UUID)
    public suspend fun revokeToken(token: String, delete: Boolean = true)
    public suspend fun verifyAccessToken(token: String): KodexPrincipal?
}
