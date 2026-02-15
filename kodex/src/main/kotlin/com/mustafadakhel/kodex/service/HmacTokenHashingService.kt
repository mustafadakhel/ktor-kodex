package com.mustafadakhel.kodex.service

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Token hashing service using HMAC-SHA256.
 *
 * Unlike salted SHA-256, HMAC-SHA256 is deterministic for a given key,
 * which allows direct database lookups by hash. The keyed nature of HMAC
 * prevents offline brute-force attacks without the secret key.
 */
internal fun hmacTokenHashingService(key: String): HashingService =
    HmacTokenHashingService(key)

private class HmacTokenHashingService(
    key: String
) : HashingService {

    private val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM)

    override fun hash(value: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        val hmacBytes = mac.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hmacBytes)
    }

    override fun verify(value: String, hash: String): Boolean {
        val computed = hash(value)
        val computedBytes = computed.toByteArray(Charsets.UTF_8)
        val storedBytes = hash.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(computedBytes, storedBytes)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
