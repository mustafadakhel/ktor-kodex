package com.mustafadakhel.kodex.tokens.token

import java.security.SecureRandom
import java.util.UUID

/**
 * Format-agnostic token generator using pluggable strategies.
 *
 * Generates tokens without knowledge of their purpose (JWT, verification, password reset, etc.).
 * Token formats are strategies that define how tokens are generated.
 */
public object TokenGenerator {
    private val secureRandom = SecureRandom()

    /**
     * Generates a token using the specified format strategy.
     */
    public fun <T> generate(format: TokenFormat<T>): T {
        return format.generate(secureRandom)
    }
}

/**
 * Strategy interface for token generation.
 *
 * Defines how a specific type of token should be generated from a random source.
 */
public interface TokenFormat<out T> {
    /**
     * Generates a token using the provided random source.
     */
    public fun generate(random: SecureRandom): T
}

// ============================================================================
// Built-in Token Formats
// ============================================================================

/**
 * UUID v4 format (random, 128-bit).
 * Best for: General-purpose unique identifiers.
 */
public object UUIDv4Format : TokenFormat<UUID> {
    override fun generate(random: SecureRandom): UUID = UUID.randomUUID()
}

/**
 * UUID v7 format (time-ordered, 128-bit).
 * Best for: Database primary keys, JWT IDs, sortable tokens.
 * Better database index performance than v4.
 */
public object UUIDv7Format : TokenFormat<UUID> {
    override fun generate(random: SecureRandom): UUID {
        val timestamp = System.currentTimeMillis()
        val randomBytes = ByteArray(10)
        random.nextBytes(randomBytes)

        // UUID v7 format: timestamp (48 bits) + version (4 bits) + random (74 bits)
        val mostSigBits = (timestamp shl 16) or
            (0x7000L or (randomBytes[0].toLong() and 0x0FFF))

        val leastSigBits = ((randomBytes[1].toLong() and 0x3F) or 0x80) shl 56 or
            (randomBytes[2].toLong() and 0xFF shl 48) or
            (randomBytes[3].toLong() and 0xFF shl 40) or
            (randomBytes[4].toLong() and 0xFF shl 32) or
            (randomBytes[5].toLong() and 0xFF shl 24) or
            (randomBytes[6].toLong() and 0xFF shl 16) or
            (randomBytes[7].toLong() and 0xFF shl 8) or
            (randomBytes[8].toLong() and 0xFF)

        return UUID(mostSigBits, leastSigBits)
    }
}

/**
 * Hexadecimal string format.
 * Best for: Verification codes, password reset tokens, session tokens.
 */
public data class HexFormat(val length: Int = 32) : TokenFormat<String> {
    init {
        require(length > 0) { "Token length must be positive" }
        require(length % 2 == 0) { "Hex token length must be even" }
    }

    override fun generate(random: SecureRandom): String {
        val bytes = ByteArray(length / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Alphanumeric string format.
 * Best for: Human-readable tokens, coupon codes, invite codes.
 */
public data class AlphanumericFormat(
    val length: Int = 32,
    val uppercase: Boolean = false
) : TokenFormat<String> {
    init {
        require(length > 0) { "Token length must be positive" }
    }

    override fun generate(random: SecureRandom): String {
        val tokenLength = length
        val chars = if (uppercase) {
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        } else {
            "0123456789abcdefghijklmnopqrstuvwxyz"
        }

        return buildString {
            repeat(tokenLength) {
                append(chars[random.nextInt(chars.length)])
            }
        }
    }
}

/**
 * Numeric code format.
 * Best for: 2FA codes, OTP, PIN codes, SMS verification.
 */
public data class NumericFormat(val length: Int = 6) : TokenFormat<String> {
    init {
        require(length in 1..18) { "Numeric code length must be between 1 and 18" }
    }

    override fun generate(random: SecureRandom): String {
        val max = "9".repeat(length).toLong()
        val code = random.nextLong(max + 1)
        return code.toString().padStart(length, '0')
    }
}

/**
 * Base64url-encoded format (RFC 4648).
 * Best for: API keys, bearer tokens, compact URL-safe tokens.
 */
public data class Base64UrlFormat(val byteLength: Int = 32) : TokenFormat<String> {
    init {
        require(byteLength > 0) { "Byte length must be positive" }
    }

    override fun generate(random: SecureRandom): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)

        return java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}

// ============================================================================
// UUID Extension Functions
// ============================================================================

/**
 * Converts UUID to 32-character hex string (removes dashes).
 */
public fun UUID.toHexString(): String = toString().replace("-", "")

/**
 * Converts UUID to compact base64url string (22 chars vs 32 chars hex).
 */
public fun UUID.toBase64Url(): String {
    val bytes = ByteArray(16)
    var mostSig = mostSignificantBits
    var leastSig = leastSignificantBits

    for (i in 7 downTo 0) {
        bytes[i] = (mostSig and 0xFF).toByte()
        mostSig = mostSig shr 8
        bytes[i + 8] = (leastSig and 0xFF).toByte()
        leastSig = leastSig shr 8
    }

    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(bytes)
}
