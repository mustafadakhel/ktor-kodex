package com.mustafadakhel.kodex.sample.senders

import com.mustafadakhel.kodex.verification.VerificationSender

/**
 * Twilio-based SMS sender for production use.
 *
 * This is a reference implementation showing how to integrate with Twilio SMS service.
 * To use this in production:
 *
 * 1. Add Twilio SDK dependency to build.gradle.kts:
 *    ```kotlin
 *    implementation("com.twilio.sdk:twilio:9.14.0")
 *    ```
 *
 * 2. Get Twilio credentials:
 *    - Sign up at https://www.twilio.com/
 *    - Get Account SID and Auth Token from Console
 *    - Get a phone number from Twilio
 *
 * 3. Configure Twilio sender:
 *    ```kotlin
 *    TwilioSmsSender(
 *        accountSid = System.getenv("TWILIO_ACCOUNT_SID"),
 *        authToken = System.getenv("TWILIO_AUTH_TOKEN"),
 *        fromPhoneNumber = "+1234567890"
 *    )
 *    ```
 *
 * Note: This implementation is commented out to avoid requiring Twilio SDK in the sample.
 * Uncomment and add the dependency when ready to use in production.
 */
class TwilioSmsSender(
    private val accountSid: String,
    private val authToken: String,
    private val fromPhoneNumber: String
) : VerificationSender {

    override suspend fun send(contactValue: String, token: String) {
        sendSms(
            to = contactValue,
            message = buildSmsMessage(token)
        )
    }

    private fun sendSms(to: String, message: String) {
        // IMPLEMENTATION NOTE:
        // Uncomment the following code when Twilio SDK dependency is added.
        // This is a production-ready Twilio implementation.

        /*
        Twilio.init(accountSid, authToken)

        try {
            val twilioMessage = Message.creator(
                PhoneNumber(to),
                PhoneNumber(fromPhoneNumber),
                message
            ).create()

            println("SMS sent successfully. SID: ${twilioMessage.sid}")
        } catch (e: ApiException) {
            throw RuntimeException("Failed to send SMS to $to: ${e.message}", e)
        }
        */

        // Placeholder implementation for demonstration
        println("Twilio SMS would be sent to: $to")
        println("From: $fromPhoneNumber")
        println("Message: $message")
        println("Add Twilio SDK dependency to enable actual SMS sending")
    }

    private fun buildSmsMessage(code: String): String {
        return "Your verification code is: $code. If you didn't request this, ignore this message."
    }
}

/**
 * Alternative implementation using HTTP API directly (without Twilio SDK).
 *
 * This can be useful if you want to avoid adding the full Twilio SDK dependency.
 */
class TwilioHttpSmsSender(
    private val accountSid: String,
    private val authToken: String,
    private val fromPhoneNumber: String
) : VerificationSender {

    override suspend fun send(contactValue: String, token: String) {
        // IMPLEMENTATION NOTE:
        // Uncomment the following code to use Twilio's REST API directly with Ktor client.
        //
        // Add Ktor client dependency:
        // implementation("io.ktor:ktor-client-core:2.x.x")
        // implementation("io.ktor:ktor-client-cio:2.x.x")
        //
        // Then use:
        /*
        val client = HttpClient(CIO)

        val credentials = "$accountSid:$authToken".encodeBase64()
        val url = "https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json"

        val response = client.post(url) {
            header("Authorization", "Basic $credentials")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("To", contactValue)
                        append("From", fromPhoneNumber)
                        append("Body", buildSmsMessage(token))
                    }
                )
            )
        }

        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to send SMS: ${response.status}")
        }

        client.close()
        */

        println("Twilio HTTP API would be used to send SMS to: $contactValue")
        println("Add Ktor client dependency to enable actual SMS sending")
    }

    private fun buildSmsMessage(code: String): String {
        return "Your verification code is: $code. If you didn't request this, ignore this message."
    }
}
