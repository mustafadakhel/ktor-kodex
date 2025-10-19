package com.mustafadakhel.kodex.service

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

internal class Argon2HashingService(
    private val algorithm: Argon2id
) : HashingService {

    private val random = SecureRandom()

    override fun hash(value: String): String {
        val salt = ByteArray(algorithm.saltLength)
        random.nextBytes(salt)

        val hash = generateArgon2Hash(value.toByteArray(Charsets.UTF_8), salt)

        return encodeHash(salt, hash)
    }

    override fun verify(value: String, hash: String): Boolean {
        if (!hash.startsWith(ARGON2ID_PREFIX)) {
            return false
        }

        val parts = hash.split('$')
        if (parts.size != 6) {
            return false
        }

        // Parse parameters: $argon2id$v=19$m=65536,t=4,p=1$salt$hash
        val params = parseParameters(parts[3])
        val salt = runCatching {
            Base64.getDecoder().decode(parts[4])
        }.getOrNull() ?: return false

        val storedHash = runCatching {
            Base64.getDecoder().decode(parts[5])
        }.getOrNull() ?: return false

        val calculatedHash = generateArgon2Hash(
            password = value.toByteArray(Charsets.UTF_8),
            salt = salt,
            memory = params.memory,
            iterations = params.iterations,
            parallelism = params.parallelism,
            hashLength = storedHash.size
        )

        // Use constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(calculatedHash, storedHash)
    }

    private fun generateArgon2Hash(
        password: ByteArray,
        salt: ByteArray,
        memory: Int = algorithm.memory,
        iterations: Int = algorithm.iterations,
        parallelism: Int = algorithm.parallelism,
        hashLength: Int = algorithm.hashLength
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13) // Version 19 (0x13)
            .withMemoryAsKB(memory)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)

        val params = builder.build()
        val generator = Argon2BytesGenerator()
        generator.init(params)

        val hash = ByteArray(hashLength)
        generator.generateBytes(password, hash)

        return hash
    }

    private fun encodeHash(salt: ByteArray, hash: ByteArray): String {
        val encodedSalt = Base64.getEncoder().withoutPadding().encodeToString(salt)
        val encodedHash = Base64.getEncoder().withoutPadding().encodeToString(hash)

        return buildString {
            append(ARGON2ID_PREFIX)
            append("v=")
            append(ARGON2_VERSION)
            append("$")
            append("m=")
            append(algorithm.memory)
            append(",t=")
            append(algorithm.iterations)
            append(",p=")
            append(algorithm.parallelism)
            append("$")
            append(encodedSalt)
            append("$")
            append(encodedHash)
        }
    }

    private fun parseParameters(paramString: String): HashParameters {
        val params = paramString.split(',').associate { part ->
            val (key, value) = part.split('=')
            key to value.toInt()
        }
        return HashParameters(
            memory = params["m"] ?: algorithm.memory,
            iterations = params["t"] ?: algorithm.iterations,
            parallelism = params["p"] ?: algorithm.parallelism
        )
    }

    private data class HashParameters(
        val memory: Int,
        val iterations: Int,
        val parallelism: Int
    )

    private companion object {
        const val ARGON2ID_PREFIX = "\$argon2id\$"
        const val ARGON2_VERSION = "19"
    }
}

internal fun argon2HashingService(
    algorithm: Argon2id = Argon2id.balanced()
): HashingService = Argon2HashingService(algorithm)
