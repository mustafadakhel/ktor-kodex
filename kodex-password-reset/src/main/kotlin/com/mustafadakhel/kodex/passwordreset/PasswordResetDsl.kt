package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * Configures password reset functionality for the realm.
 *
 * @param sender Password reset sender for notifications (email, SMS, etc.)
 * @param block Optional configuration block for advanced settings
 */
public fun RealmConfigScope.passwordReset(
    sender: PasswordResetSender,
    block: PasswordResetConfig.() -> Unit = {}
) {
    extension(PasswordResetConfig()) {
        passwordResetSender = sender
        block()
    }
}
