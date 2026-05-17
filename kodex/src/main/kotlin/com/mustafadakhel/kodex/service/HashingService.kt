package com.mustafadakhel.kodex.service

public interface HashingService {
    public fun hash(value: String): String
    public fun verify(value: String, hash: String): Boolean
}

public fun passwordHashingService(
    algorithm: Argon2id = Argon2id.balanced()
): HashingService = argon2HashingService(algorithm)
