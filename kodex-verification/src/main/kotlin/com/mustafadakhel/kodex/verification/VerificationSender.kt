package com.mustafadakhel.kodex.verification

/**
 * Interface for sending verification tokens to contacts.
 *
 * Library users implement this interface to integrate with their
 * email providers, SMS gateways, or custom notification systems.
 *
 * Example implementations:
 * ```kotlin
 * class EmailVerificationSender(private val emailProvider: EmailProvider) : VerificationSender {
 *     override val contactType = ContactIdentifier(ContactType.EMAIL)
 *
 *     override suspend fun send(contactValue: String, token: String) {
 *         emailProvider.sendEmail(
 *             to = contactValue,
 *             subject = "Verify your email",
 *             body = "Click here: /verify/email?token=$token"
 *         )
 *     }
 * }
 *
 * class SMSVerificationSender(private val twilioClient: TwilioClient) : VerificationSender {
 *     override val contactType = ContactIdentifier(ContactType.PHONE)
 *
 *     override suspend fun send(contactValue: String, token: String) {
 *         twilioClient.sendSMS(
 *             to = contactValue,
 *             message = "Your verification code: $token"
 *         )
 *     }
 * }
 *
 * class DiscordVerificationSender(private val bot: DiscordBot) : VerificationSender {
 *     override val contactType = ContactIdentifier(ContactType.CUSTOM_ATTRIBUTE, "discord")
 *
 *     override suspend fun send(contactValue: String, token: String) {
 *         bot.sendDM(username = contactValue, message = "Verify: /verify/discord?token=$token")
 *     }
 * }
 * ```
 */
public interface VerificationSender {

    /**
     * Send a verification token to the contact.
     *
     * @param contactValue The contact value (email address, phone number, discord username, etc.)
     * @param token The verification token to send
     */
    public suspend fun send(contactValue: String, token: String)
}
