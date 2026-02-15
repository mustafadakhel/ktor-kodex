package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.extension.ExtensionConfig
import com.mustafadakhel.kodex.extension.ExtensionContext
import io.ktor.utils.io.*
import kotlinx.datetime.TimeZone

@KtorDsl
public class AccountLockoutConfig : ExtensionConfig() {

    /** The lockout policy to use (default: moderate - 5 attempts, 30 min lockout) */
    public var policy: AccountLockoutPolicy = AccountLockoutPolicy.moderate()

    override fun build(context: ExtensionContext): AccountLockoutExtension {
        val service = accountLockoutService(policy, context.timeZone, context.realm.name)
        return AccountLockoutExtension(service, context.timeZone)
    }
}
