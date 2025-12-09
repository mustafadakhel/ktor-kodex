package com.mustafadakhel.kodex.mfa

import com.mustafadakhel.kodex.mfa.sender.MfaCodeSender
import java.util.concurrent.ConcurrentHashMap

class MockEmailSender : MfaCodeSender {
    private val sentCodes = ConcurrentHashMap<String, String>()

    override suspend fun send(contactValue: String, code: String) {
        sentCodes[contactValue] = code
    }

    fun getLastCode(contact: String): String? = sentCodes[contact]

    fun clear() {
        sentCodes.clear()
    }
}