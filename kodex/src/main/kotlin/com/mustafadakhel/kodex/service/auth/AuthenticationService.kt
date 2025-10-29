package com.mustafadakhel.kodex.service.auth

import com.mustafadakhel.kodex.token.TokenPair
import java.util.UUID

/**
 * Service responsible for authentication flows and password management.
 *
 * This service handles all authentication-related operations including:
 * - Credential-based authentication (email/phone + password)
 * - Password changes and resets
 * - Security-critical operations (timing attack prevention, account lockout checks)
 *
 * Security features:
 * - **Constant-time authentication**: Prevents timing-based user enumeration
 * - **Dummy hash verification**: When user doesn't exist, verifies against dummy hash
 * - **Hook execution**: Calls beforeLogin and afterLoginFailure hooks
 * - **Audit events**: Publishes detailed auth events for security monitoring
 *
 * All authentication methods return TokenPair on success or throw
 * Authorization exceptions on failure.
 */
public interface AuthenticationService {

    // ========== Authentication Flows ==========

    /**
     * Authenticates a user by email and password.
     *
     * This operation:
     * 1. Executes beforeLogin hooks (e.g., account lockout check)
     * 2. Verifies credentials in constant time (timing attack prevention)
     * 3. Checks verification status
     * 4. Updates last login timestamp
     * 5. Publishes LoginSuccess event
     * 6. Generates and returns token pair
     *
     * On failure:
     * - Executes afterLoginFailure hooks
     * - Publishes LoginFailed event with server-side reason
     * - Throws InvalidCredentials (same exception regardless of reason)
     *
     * @param email User's email address
     * @param password Plain-text password to verify
     * @return TokenPair with access and refresh tokens
     * @throws InvalidCredentials if credentials are invalid (user not found OR wrong password)
     * @throws UnverifiedAccount if user exists but is not verified
     * @throws AccountLocked if account is locked (via hooks)
     */
    public suspend fun tokenByEmail(email: String, password: String): TokenPair

    /**
     * Authenticates a user by phone number and password.
     *
     * Identical behavior to tokenByEmail() but uses phone number instead of email.
     *
     * @param phone User's phone number
     * @param password Plain-text password to verify
     * @return TokenPair with access and refresh tokens
     * @throws InvalidCredentials if credentials are invalid
     * @throws UnverifiedAccount if user exists but is not verified
     * @throws AccountLocked if account is locked (via hooks)
     */
    public suspend fun tokenByPhone(phone: String, password: String): TokenPair

    // ========== Password Management ==========

    /**
     * Changes a user's password after verifying the old password.
     *
     * This is a user-initiated operation requiring knowledge of the current password.
     * It publishes PasswordChanged event on success or PasswordChangeFailed on failure.
     *
     * @param userId The user whose password to change
     * @param oldPassword Current password for verification
     * @param newPassword New password to set
     * @throws InvalidCredentials if old password is incorrect
     * @throws UserNotFound if user doesn't exist
     */
    public suspend fun changePassword(userId: UUID, oldPassword: String, newPassword: String)

    /**
     * Resets a user's password without requiring the old password.
     *
     * This is an admin-initiated operation or used in password recovery flows.
     * It does NOT verify the old password. It publishes PasswordReset event.
     *
     * @param userId The user whose password to reset
     * @param newPassword New password to set
     * @throws UserNotFound if user doesn't exist
     */
    public suspend fun resetPassword(userId: UUID, newPassword: String)
}
