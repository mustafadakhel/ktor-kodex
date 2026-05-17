package com.mustafadakhel.kodex.sample.senders

import com.mustafadakhel.kodex.verification.VerificationSender

/**
 * Console-based SMS sender for development and testing.
 *
 * Prints SMS content to console instead of sending actual text messages.
 * Use this during development to test phone verification flows
 * without needing SMS service configuration.
 *
 * For production, replace with an actual SMS service like:
 * - Twilio
 * - AWS SNS
 * - MessageBird
 * - Vonage (formerly Nexmo)
 */
class ConsoleSmsSender : VerificationSender {

    override suspend fun send(contactValue: String, token: String) {
        println("\n" + "=".repeat(80))
        println("SMS MESSAGE")
        println("=".repeat(80))
        println("To: $contactValue")
        println("\n${buildSmsMessage(token)}")
        println("=".repeat(80) + "\n")
    }

    private fun buildSmsMessage(code: String): String {
        return "Your verification code is: $code. If you didn't request this, ignore this message."
    }
}
