package com.mustafadakhel.kodex.service

public data class Argon2id(
    val memory: Int = 65536,
    val iterations: Int = 4,
    val parallelism: Int = 1,
    val saltLength: Int = 16,
    val hashLength: Int = 32
) {
    init {
        require(memory > 0) { "Memory must be positive" }
        require(iterations > 0) { "Iterations must be positive" }
        require(parallelism > 0) { "Parallelism must be positive" }
        require(saltLength >= 8) { "Salt must be at least 8 bytes" }
        require(hashLength >= 16) { "Hash must be at least 16 bytes" }
    }

    public companion object {
        public fun springSecurity(): Argon2id = Argon2id(memory = 60000, iterations = 10, parallelism = 1)
        public fun keycloak(): Argon2id = Argon2id(memory = 7168, iterations = 5, parallelism = 1)
        public fun owaspMinimum(): Argon2id = Argon2id(memory = 19456, iterations = 2, parallelism = 1)
        public fun balanced(): Argon2id = Argon2id()
    }
}
