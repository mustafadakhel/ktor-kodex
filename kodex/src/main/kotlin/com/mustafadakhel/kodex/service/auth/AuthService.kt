package com.mustafadakhel.kodex.service.auth

import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

/**
 * Service for authentication and password management.
 *
 * Handles user login flows (email/phone), password changes,
 * and password resets.
 */
public interface AuthService {

    /** Authenticates user by email and returns access/refresh tokens. */
    public suspend fun login(
        email: String,
        password: String,
        ipAddress: String,
        userAgent: String?
    ): TokenPair

    /** Authenticates user by phone and returns access/refresh tokens. */
    public suspend fun loginByPhone(
        phone: String,
        password: String,
        ipAddress: String,
        userAgent: String?
    ): TokenPair

    /** Changes user password after verifying old password. */
    public suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String)

    /** Resets user password without verifying old password (admin operation). */
    public suspend fun resetPassword(userId: UUID, newPassword: String)
}
