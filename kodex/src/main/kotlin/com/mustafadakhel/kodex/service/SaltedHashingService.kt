package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.service.SaltedHashingService.SaltedHashSettings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

internal interface SaltedHashingService : HashingService {
    data class SaltedHashSettings(
        val algorithm: String,
        val saltLength: Int,
    )
}

internal fun saltedHashingService(
    settings: SaltedHashSettings = SaltedHashSettings(
        algorithm = "SHA-256",
        saltLength = 16
    )
): SaltedHashingService = DefaultSaltedHashingService(settings = settings)

private class DefaultSaltedHashingService(
    private val settings: SaltedHashSettings
) : SaltedHashingService {

    private val random = SecureRandom()

    override fun hash(value: String): String {
        val saltLength = settings.saltLength
        val algorithm = settings.algorithm
        val salt = ByteArray(saltLength)
        random.nextBytes(salt)
        val digest = MessageDigest.getInstance(algorithm)
        digest.update(salt)
        val hashed = digest.digest(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(salt.size + hashed.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(hashed, 0, combined, salt.size, hashed.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    override fun verify(value: String, hash: String): Boolean {
        val saltLength = settings.saltLength
        val algorithm = settings.algorithm
        val bytes = runCatching { Base64.getDecoder().decode(hash) }.getOrNull() ?: return false
        if (bytes.size <= saltLength) return false
        val salt = bytes.copyOfRange(0, saltLength)
        val storedHash = bytes.copyOfRange(saltLength, bytes.size)
        val digest = MessageDigest.getInstance(algorithm)
        digest.update(salt)
        val calculated = digest.digest(value.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(calculated, storedHash)
    }
}
