package com.mustafadakhel.kodex.service.auth

import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

/** Handles authentication and password management. */
public interface AuthenticationService {

    /** Authenticates user by email and returns access/refresh tokens. */
    public suspend fun tokenByEmail(email: String, password: String): TokenPair

    /** Authenticates user by phone and returns access/refresh tokens. */
    public suspend fun tokenByPhone(phone: String, password: String): TokenPair

    /** Changes user password after verifying old password. */
    public suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String)

    /** Resets user password without verifying old password (admin operation). */
    public suspend fun resetPassword(userId: UUID, newPassword: String)
}
