package com.mustafadakhel.kodex.passwordreset

import com.mustafadakhel.kodex.routes.auth.RealmConfigScope

/**
 * Configures password reset functionality for the realm.
 *
 * @param block Configuration block — must set passwordResetSender inside
 */
public fun RealmConfigScope.passwordReset(
    block: PasswordResetConfig.() -> Unit
) {
    extension(PasswordResetConfig(), block)
}
