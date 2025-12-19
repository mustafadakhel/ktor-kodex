package com.mustafadakhel.kodex.mfa.totp

import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.security.MessageDigest

public class TotpValidator(
    private val generator: TotpGenerator,
    private val timeStepWindow: Int = 1
) {

    init {
        require(timeStepWindow in 0..2) { "Time step window must be 0-2" }
    }

    public fun validate(
        secret: String,
        code: String,
        timestamp: Instant = CurrentKotlinInstant
    ): Boolean {
        val normalizedCode = code.padStart(generator.digits, '0')

        for (offset in -timeStepWindow..timeStepWindow) {
            val checkTime = timestamp + (generator.period * offset)
            val expectedCode = generator.generateCode(secret, checkTime)

            if (constantTimeEquals(normalizedCode, expectedCode)) {
                return true
            }
        }

        return false
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }

        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)

        return MessageDigest.isEqual(aBytes, bBytes)
    }
}
