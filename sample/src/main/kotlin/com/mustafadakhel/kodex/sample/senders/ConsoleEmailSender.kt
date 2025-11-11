package com.mustafadakhel.kodex.sample.senders

import com.mustafadakhel.kodex.passwordreset.PasswordResetSender
import com.mustafadakhel.kodex.verification.VerificationSender

/**
 * Console-based email sender for development and testing.
 *
 * Prints email content to console instead of sending actual emails.
 * Use this during development to test verification and password reset flows
 * without needing SMTP configuration.
 *
 * For production, replace with an actual email service like:
 * - SMTP via JavaMail API
 * - SendGrid
 * - AWS SES
 * - Mailgun
 * - Postmark
 */
/**
 * Console email sender that implements both VerificationSender and PasswordResetSender.
 * Uses separate classes internally to distinguish between verification and password reset emails.
 */
class ConsoleEmailSender {
    val verificationSender = ConsoleVerificationEmailSender()
    val passwordResetSender = ConsolePasswordResetEmailSender()
}

class ConsoleVerificationEmailSender : VerificationSender {
    override suspend fun send(contactValue: String, token: String) {
        println("\n" + "=".repeat(80))
        println("VERIFICATION EMAIL")
        println("=".repeat(80))
        println("To: $contactValue")
        println("Subject: Verify your email address")
        println("\n${buildVerificationEmailBody(token)}")
        println("=".repeat(80) + "\n")
    }

    private fun buildVerificationEmailBody(code: String): String {
        return """
            Hello,

            Please verify your email address by entering the following code:

            Verification Code: $code

            If you didn't request this verification, please ignore this email.

            Best regards,
            Your App Team
        """.trimIndent()
    }
}

class ConsolePasswordResetEmailSender : PasswordResetSender {
    override suspend fun send(recipient: String, token: String, expiresAt: String) {
        println("\n" + "=".repeat(80))
        println("PASSWORD RESET EMAIL")
        println("=".repeat(80))
        println("To: $recipient")
        println("Subject: Reset your password")
        println("\n${buildPasswordResetEmailBody(token, expiresAt)}")
        println("=".repeat(80) + "\n")
    }

    private fun buildPasswordResetEmailBody(token: String, expiresAt: String): String {
        return """
            Hello,

            You requested to reset your password. Please use the following token:

            Reset Token: $token

            This token expires at: $expiresAt

            To reset your password, visit:
            https://yourapp.com/reset-password?token=$token

            If you didn't request a password reset, please ignore this email.

            Best regards,
            Your App Team
        """.trimIndent()
    }
}
