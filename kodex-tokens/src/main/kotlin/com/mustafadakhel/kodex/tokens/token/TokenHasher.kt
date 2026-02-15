package com.mustafadakhel.kodex.tokens.token

import java.security.MessageDigest

/**
 * One-way SHA-256 hashing for tokens stored in the database.
 *
 * Tokens are generated and sent to the user in plaintext, but only the
 * SHA-256 hash is persisted. On verification, the user-provided token
 * is hashed and compared against the stored hash.
 *
 * This prevents token theft if the database is compromised.
 */
public object TokenHasher {

    /**
     * Hashes a plaintext token using SHA-256, returning the hex-encoded digest.
     *
     * The output is always 64 lowercase hex characters.
     */
    public fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
