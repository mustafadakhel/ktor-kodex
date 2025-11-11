package com.mustafadakhel.kodex.sample.senders

import com.mustafadakhel.kodex.passwordreset.PasswordResetSender
import com.mustafadakhel.kodex.verification.VerificationSender

/**
 * SMTP-based email sender for production use.
 *
 * This is a reference implementation showing how to integrate with SMTP email services.
 * To use this in production:
 *
 * 1. Add JavaMail dependency to build.gradle.kts:
 *    ```kotlin
 *    implementation("com.sun.mail:jakarta.mail:2.0.1")
 *    ```
 *
 * 2. Configure SMTP settings (example for Gmail):
 *    ```kotlin
 *    val senders = SmtpEmailSender(
 *        host = "smtp.gmail.com",
 *        port = 587,
 *        username = "your-email@gmail.com",
 *        password = "your-app-password",
 *        fromEmail = "noreply@yourapp.com",
 *        fromName = "Your App"
 *    )
 *    // Use senders.verificationSender for verification
 *    // Use senders.passwordResetSender for password reset
 *    ```
 *
 * 3. For other providers:
 *    - SendGrid: smtp.sendgrid.net:587
 *    - Mailgun: smtp.mailgun.org:587
 *    - AWS SES: email-smtp.{region}.amazonaws.com:587
 *
 * Note: This implementation is commented out to avoid requiring JavaMail dependency in the sample.
 * Uncomment and add the dependency when ready to use in production.
 */
class SmtpEmailSender(
    host: String,
    port: Int,
    username: String,
    password: String,
    fromEmail: String,
    fromName: String,
    useTls: Boolean = true
) {
    val verificationSender = SmtpVerificationEmailSender(host, port, username, password, fromEmail, fromName, useTls)
    val passwordResetSender = SmtpPasswordResetEmailSender(host, port, username, password, fromEmail, fromName, useTls)
}

class SmtpVerificationEmailSender(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val fromEmail: String,
    private val fromName: String,
    private val useTls: Boolean = true
) : VerificationSender {

    override suspend fun send(contactValue: String, token: String) {
        sendEmail(
            to = contactValue,
            subject = "Verify your email address",
            htmlBody = buildVerificationHtml(token),
            textBody = buildVerificationText(token)
        )
    }

    private fun sendEmail(to: String, subject: String, htmlBody: String, textBody: String) {
        println("SMTP Email would be sent to: $to")
        println("Subject: $subject")
        println("Add JavaMail dependency to enable actual email sending")
    }

    private fun buildVerificationHtml(code: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Verify Your Email</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; }
                    .code-box { background: #f0f0f0; padding: 20px; text-align: center; margin: 20px 0; }
                    .code { font-size: 32px; font-weight: bold; color: #007bff; font-family: monospace; }
                </style>
            </head>
            <body>
                <h1>Verify Your Email</h1>
                <p>Please enter the following verification code:</p>
                <div class="code-box"><div class="code">$code</div></div>
                <p>If you didn't request this, please ignore this email.</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildVerificationText(code: String): String {
        return "Your verification code is: $code"
    }
}

class SmtpPasswordResetEmailSender(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val fromEmail: String,
    private val fromName: String,
    private val useTls: Boolean = true
) : PasswordResetSender {

    override suspend fun send(recipient: String, token: String, expiresAt: String) {
        sendEmail(
            to = recipient,
            subject = "Reset your password",
            htmlBody = buildPasswordResetHtml(token, expiresAt),
            textBody = buildPasswordResetText(token, expiresAt)
        )
    }

    private fun sendEmail(to: String, subject: String, htmlBody: String, textBody: String) {
        println("SMTP Email would be sent to: $to")
        println("Subject: $subject")
        println("Add JavaMail dependency to enable actual email sending")
    }

    private fun buildPasswordResetHtml(token: String, expiresAt: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Reset Your Password</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; }
                    .button { display: inline-block; background: #007bff; color: white; padding: 12px 30px; text-decoration: none; border-radius: 4px; }
                    .warning { background: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 20px 0; }
                </style>
            </head>
            <body>
                <h1>Reset Your Password</h1>
                <p>Click the button below to reset your password:</p>
                <p><a href="https://yourapp.com/reset-password?token=$token" class="button">Reset Password</a></p>
                <p><strong>Expires:</strong> $expiresAt</p>
                <div class="warning">⚠️ If you didn't request this, please ignore this email.</div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildPasswordResetText(token: String, expiresAt: String): String {
        return """
            Reset Your Password

            Visit: https://yourapp.com/reset-password?token=$token
            Expires: $expiresAt

            If you didn't request this, please ignore this email.
        """.trimIndent()
    }
}
