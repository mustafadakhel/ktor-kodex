package com.mustafadakhel.kodex.lockout

import com.mustafadakhel.kodex.throwable.KodexThrowable
import kotlinx.datetime.LocalDateTime

public open class LockoutThrowable(
    message: String? = null,
    cause: Throwable? = null
) : KodexThrowable(message, cause) {

    public data class AccountLocked(
        val lockedUntil: LocalDateTime,
        val reason: String
    ) : LockoutThrowable("Account is locked until $lockedUntil. Reason: $reason")

    public data class TooManyAttempts(
        val reason: String
    ) : LockoutThrowable("Too many login attempts. $reason")
}
