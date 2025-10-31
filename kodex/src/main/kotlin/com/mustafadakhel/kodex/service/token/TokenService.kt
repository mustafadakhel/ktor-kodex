package com.mustafadakhel.kodex.service.token

import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

/**
 * Service for token lifecycle management.
 *
 * Handles token issuance, refresh, revocation, and verification.
 */
public interface TokenService {
    public suspend fun issue(userId: UUID): TokenPair
    public suspend fun refresh(userId: UUID, refreshToken: String): TokenPair
    public suspend fun revoke(userId: UUID)
    public suspend fun revokeToken(token: String, delete: Boolean = true)
    public suspend fun verify(token: String): KodexPrincipal?
}
