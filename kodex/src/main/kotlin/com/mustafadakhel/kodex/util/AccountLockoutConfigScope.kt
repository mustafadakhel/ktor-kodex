package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.security.AccountLockoutPolicy
import io.ktor.utils.io.*

internal data class AccountLockoutConfig(
    val policy: AccountLockoutPolicy
)

@KtorDsl
public class AccountLockoutConfigScope internal constructor() {
    public var policy: AccountLockoutPolicy = AccountLockoutPolicy.moderate()

    internal fun build(): AccountLockoutConfig = AccountLockoutConfig(
        policy = policy
    )
}
