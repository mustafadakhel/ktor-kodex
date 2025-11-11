package com.mustafadakhel.kodex.verification

import com.mustafadakhel.kodex.throwable.KodexThrowable

public open class VerificationThrowable(
    message: String? = null,
    cause: Throwable? = null
) : KodexThrowable(message, cause) {

    public data object UnverifiedAccount : VerificationThrowable("Account not verified") {
        private fun readResolve(): Any = UnverifiedAccount
    }
}
