package com.mustafadakhel.kodex.service.auth

import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

public interface AuthService {
    public suspend fun login(
        email: String,
        password: String,
        ipAddress: String,
        userAgent: String?
    ): TokenPair

    public suspend fun loginByPhone(
        phone: String,
        password: String,
        ipAddress: String,
        userAgent: String?
    ): TokenPair

    public suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String)

    /** Resets user password without verifying old password (admin operation). */
    public suspend fun resetPassword(userId: UUID, newPassword: String)
}
