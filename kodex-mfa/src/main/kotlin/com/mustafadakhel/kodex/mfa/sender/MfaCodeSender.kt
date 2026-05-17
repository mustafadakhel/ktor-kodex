package com.mustafadakhel.kodex.mfa.sender

public interface MfaCodeSender {
    public suspend fun send(contactValue: String, code: String)
}
