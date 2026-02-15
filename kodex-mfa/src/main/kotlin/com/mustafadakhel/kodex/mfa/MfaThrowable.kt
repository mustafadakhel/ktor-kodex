package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.throwable.KodexThrowable

public sealed class MfaThrowable(
    message: String? = null,
    cause: Throwable? = null
) : KodexThrowable(message, cause) {

    public data class MfaRequired(
        val sessionId: String,
        val availableMethods: List<String>
    ) : MfaThrowable("MFA verification required")

    public data class MfaEnrollmentRequired(
        override val message: String = "MFA enrollment required"
    ) : MfaThrowable(message)
}
