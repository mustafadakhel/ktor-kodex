package com.mustafadakhel.kodex.tokens.token

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength

class TokenHasherTest : StringSpec({

    "hash produces 64-character lowercase hex string" {
        val hash = TokenHasher.hash("test-token")
        hash shouldHaveLength 64
        hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    "hash is deterministic for same input" {
        val hash1 = TokenHasher.hash("same-token")
        val hash2 = TokenHasher.hash("same-token")
        hash1 shouldBe hash2
    }

    "hash produces different output for different inputs" {
        val hash1 = TokenHasher.hash("token-a")
        val hash2 = TokenHasher.hash("token-b")
        hash1 shouldNotBe hash2
    }

    "hash produces known SHA-256 value" {
        // SHA-256 of "hello" is well-known
        val hash = TokenHasher.hash("hello")
        hash shouldBe "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }

    "hash handles empty string" {
        val hash = TokenHasher.hash("")
        hash shouldHaveLength 64
        // SHA-256 of empty string
        hash shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
})
