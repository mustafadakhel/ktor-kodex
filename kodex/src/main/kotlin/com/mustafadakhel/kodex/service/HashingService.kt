package com.mustafadakhel.kodex.service

internal interface HashingService {
    fun hash(value: String): String
    fun verify(value: String, hash: String): Boolean
}

internal fun passwordHashingService(
    algorithm: Argon2id = Argon2id.balanced()
): HashingService = argon2HashingService(algorithm)
