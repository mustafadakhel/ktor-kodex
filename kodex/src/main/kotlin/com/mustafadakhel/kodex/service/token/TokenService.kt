package com.mustafadakhel.kodex.service.token

import com.mustafadakhel.kodex.routes.auth.KodexPrincipal
import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

/**
 * Service responsible for token lifecycle management.
 *
 * This service handles all token-related operations including generation,
 * refresh, revocation, and verification. It delegates to the underlying
 * TokenManager for JWT operations.
 *
 * Responsibilities:
 * - Initial token generation (after authentication)
 * - Token refresh with rotation
 * - Token revocation (single + bulk)
 * - Access token verification
 */
public interface TokenService {
    /**
     * Issues a new token pair for a user (initial generation).
     *
     * This is typically called after successful authentication to generate
     * the initial access and refresh tokens.
     *
     * @param userId The user ID to issue tokens for
     * @return New TokenPair with access and refresh tokens
     */
    public suspend fun issueTokens(userId: UUID): TokenPair

    /**
     * Refreshes an existing refresh token and returns a new token pair.
     *
     * @param userId The user ID associated with the refresh token
     * @param refreshToken The refresh token to exchange
     * @return New TokenPair with fresh access and refresh tokens
     * @throws InvalidToken if the refresh token is invalid or expired
     * @throws TokenReplayDetected if token reuse is detected outside grace period
     */
    public suspend fun refresh(userId: UUID, refreshToken: String): TokenPair

    /**
     * Revokes all tokens for a specific user.
     * Useful for logout-from-all-devices functionality.
     *
     * @param userId The user whose tokens should be revoked
     */
    public fun revokeTokens(userId: UUID)

    /**
     * Revokes a specific token by its value.
     *
     * @param token The token value to revoke
     * @param delete Whether to delete the token from persistence (default: true)
     */
    public fun revokeToken(token: String, delete: Boolean = true)

    /**
     * Verifies an access token and returns the associated principal.
     *
     * @param token The access token to verify
     * @return KodexPrincipal if token is valid, null otherwise
     */
    public fun verifyAccessToken(token: String): KodexPrincipal?
}
