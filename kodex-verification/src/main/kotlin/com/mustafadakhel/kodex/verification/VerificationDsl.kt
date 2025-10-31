package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * DSL function for configuring the verification extension.
 *
 * Example usage:
 * ```kotlin
 * realm("admin") {
 *     verification {
 *         strategy = VerificationConfig.VerificationStrategy.VERIFY_ALL_PROVIDED
 *         defaultTokenExpiration = 24.hours
 *
 *         email {
 *             required = true
 *             autoSend = true
 *             tokenExpiration = 24.hours
 *             sender = EmailVerificationSender(emailProvider)
 *         }
 *
 *         phone {
 *             required = false
 *             autoSend = true
 *             tokenExpiration = 10.minutes
 *             sender = SMSVerificationSender(twilioClient)
 *         }
 *
 *         customAttribute("discord") {
 *             required = false
 *             autoSend = false
 *             tokenExpiration = 30.minutes
 *             sender = DiscordVerificationSender(discordBot)
 *         }
 *     }
 * }
 * ```
 */
public fun RealmConfigScope.verification(block: VerificationConfig.() -> Unit) {
    extension(VerificationConfig(), block)
}
