package com.mustafadakhel.kodex.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class HmacTokenHashingServiceTest : StringSpec({
    val key = "test-hmac-key-for-token-hashing"
    val hashingService = hmacTokenHashingService(key)

    "hash should produce deterministic output for the same input and key" {
        val hash1 = hashingService.hash("token-value")
        val hash2 = hashingService.hash("token-value")
        hash1 shouldBe hash2
    }

    "hash should produce different output for different inputs" {
        val hash1 = hashingService.hash("token-a")
        val hash2 = hashingService.hash("token-b")
        hash1 shouldNotBe hash2
    }

    "hash should produce different output for the same input with different keys" {
        val service1 = hmacTokenHashingService("key-one")
        val service2 = hmacTokenHashingService("key-two")
        val hash1 = service1.hash("same-token")
        val hash2 = service2.hash("same-token")
        hash1 shouldNotBe hash2
    }

    "verify should return true for matching value" {
        val hash = hashingService.hash("my-refresh-token")
        hashingService.verify("my-refresh-token", hash).shouldBeTrue()
    }

    "verify should return false for non-matching value" {
        val hash = hashingService.hash("my-refresh-token")
        hashingService.verify("other-token", hash).shouldBeFalse()
    }

    "verify should return false for tampered hash" {
        val hash = hashingService.hash("my-refresh-token")
        val tampered = hash.replaceFirst('A', 'B')
        if (tampered != hash) {
            hashingService.verify("my-refresh-token", tampered).shouldBeFalse()
        }
    }

    "hash output should be base64 encoded" {
        val hash = hashingService.hash("test-token")
        val decoded = runCatching {
            java.util.Base64.getDecoder().decode(hash)
        }
        decoded.isSuccess.shouldBeTrue()
        decoded.getOrThrow().size shouldBe 32 // HMAC-SHA256 produces 32 bytes
    }
})
