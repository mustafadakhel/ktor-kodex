package com.mustafadakhel.kodex.mfa.totp

import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.Instant
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class TotpAlgorithm {
    SHA1,
    SHA256,
    SHA512
}

public class TotpGenerator(
    internal val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    internal val digits: Int = 6,
    internal val period: Duration = 30.seconds
) {
    private val secureRandom = SecureRandom()

    init {
        require(digits in 6..8) { "TOTP digits must be 6, 7, or 8" }
        require(period.inWholeSeconds in 15..120) { "TOTP period must be 15-120 seconds" }
    }

    private fun toHmacAlgorithm(): HmacAlgorithm = when (algorithm) {
        TotpAlgorithm.SHA1 -> HmacAlgorithm.SHA1
        TotpAlgorithm.SHA256 -> HmacAlgorithm.SHA256
        TotpAlgorithm.SHA512 -> HmacAlgorithm.SHA512
    }

    public fun generateSecret(): String {
        val secretLength = when (algorithm) {
            TotpAlgorithm.SHA1 -> 20
            TotpAlgorithm.SHA256 -> 32
            TotpAlgorithm.SHA512 -> 64
        }

        val secretBytes = ByteArray(secretLength)
        secureRandom.nextBytes(secretBytes)

        return Base32.encode(secretBytes)
    }

    public fun generateCode(
        secret: String,
        timestamp: Instant = CurrentKotlinInstant
    ): String {
        val secretBytes = Base32.decode(secret)

        val config = TimeBasedOneTimePasswordConfig(
            codeDigits = digits,
            hmacAlgorithm = toHmacAlgorithm(),
            timeStep = period.inWholeSeconds,
            timeStepUnit = TimeUnit.SECONDS
        )

        val generator = TimeBasedOneTimePasswordGenerator(secretBytes, config)
        val code = generator.generate(timestamp.toEpochMilliseconds())

        return code.padStart(digits, '0')
    }

    public fun generateQrCodeUri(
        secret: String,
        issuer: String,
        accountName: String
    ): String {
        val algorithmName = algorithm.name
        val periodSeconds = period.inWholeSeconds

        return buildString {
            append("otpauth://totp/")
            append(urlEncode(issuer))
            append(":")
            append(urlEncode(accountName))
            append("?secret=")
            append(secret)
            append("&issuer=")
            append(urlEncode(issuer))
            append("&algorithm=")
            append(algorithmName)
            append("&digits=")
            append(digits)
            append("&period=")
            append(periodSeconds)
        }
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }
}

public object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val DECODE_MAP = ALPHABET.withIndex().associate { (index, char) -> char to index }

    public fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8

            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                result.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }

        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(ALPHABET[index])
        }

        while (result.length % 8 != 0) {
            result.append('=')
        }

        return result.toString()
    }

    public fun decode(encoded: String): ByteArray {
        val cleanInput = encoded.uppercase().replace("=", "")
        if (cleanInput.isEmpty()) return ByteArray(0)

        val result = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (char in cleanInput) {
            val value = DECODE_MAP[char] ?: throw IllegalArgumentException("Invalid Base32 character: $char")
            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                result.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }

        return result.toByteArray()
    }
}
