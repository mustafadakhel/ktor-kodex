package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * Configure Multi-Factor Authentication (MFA) for enhanced security.
 *
 * Example:
 * ```kotlin
 * realm("admin") {
 *     mfa {
 *         requireMfa = false // Optional by default - users can enable MFA voluntarily
 *
 *         emailMfa {
 *             enabled = true
 *             sender = object : MfaCodeSender {
 *                 override suspend fun send(contactValue: String, code: String) {
 *                     // Send MFA code via email
 *                 }
 *             }
 *         }
 *
 *         totpMfa {
 *             enabled = true
 *             issuer = "MyApp"
 *             algorithm = TotpAlgorithm.SHA1
 *             digits = 6
 *             period = 30.seconds
 *         }
 *
 *         backupCodes {
 *             codeCount = 10
 *             codeLength = 8
 *         }
 *
 *         encryption {
 *             aesGcm("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF")
 *         }
 *
 *         sessionExpiration = 5.minutes
 *         maxActiveSessions = 3
 *         codeExpiration = 10.minutes
 *     }
 * }
 * ```
 */
public fun RealmConfigScope.mfa(block: MfaConfig.() -> Unit) {
    extension(MfaConfig(), block)
}
