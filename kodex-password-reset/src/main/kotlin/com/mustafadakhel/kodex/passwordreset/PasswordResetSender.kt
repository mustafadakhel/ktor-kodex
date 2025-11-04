package com.mustafadakhel.kodex.passwordreset

/**
 * Interface for sending password reset notifications.
 *
 * Implementations can send via email, SMS, or other channels.
 */
public interface PasswordResetSender {
    /**
     * Sends a password reset token to the specified contact.
     *
     * @param recipient The recipient identifier (email address, phone number, etc.)
     * @param token The password reset token
     * @param expiresAt Expiration timestamp as a string for display
     */
    public suspend fun send(
        recipient: String,
        token: String,
        expiresAt: String
    )
}
