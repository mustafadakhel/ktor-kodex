package com.mustafadakhel.kodex.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.util.*

class Argon2HashingServiceTest : StringSpec({

    "hash should generate Argon2id formatted hash" {
        val service = argon2HashingService()
        val hash = service.hash("password123")

        hash shouldStartWith "\$argon2id\$v=19\$"
    }

    "hash should generate unique salts for same password" {
        val service = argon2HashingService()
        val hash1 = service.hash("password")
        val hash2 = service.hash("password")

        hash1 shouldBe hash1 // sanity check
        (hash1 == hash2).shouldBeFalse()
    }

    "hash format should include parameters" {
        val algorithm = Argon2id(
            memory = 65536,
            iterations = 4,
            parallelism = 1
        )
        val service = argon2HashingService(algorithm)
        val hash = service.hash("test")

        hash shouldStartWith "\$argon2id\$v=19\$m=65536,t=4,p=1\$"
    }

    "verify should return true for correct password" {
        val service = argon2HashingService()
        val password = "SecurePassword123!"
        val hash = service.hash(password)

        service.verify(password, hash).shouldBeTrue()
    }

    "verify should return false for incorrect password" {
        val service = argon2HashingService()
        val hash = service.hash("correct")

        service.verify("incorrect", hash).shouldBeFalse()
    }

    "verify should return false for malformed hash" {
        val service = argon2HashingService()

        service.verify("password", "not-a-valid-hash").shouldBeFalse()
    }

    "verify should return false for non-Argon2id hash" {
        val service = argon2HashingService()
        val sha256Hash = "c29tZS1zaGEyNTYtaGFzaA=="

        service.verify("password", sha256Hash).shouldBeFalse()
    }

    "verify should return false for truncated hash" {
        val service = argon2HashingService()
        val hash = service.hash("password")
        val truncated = hash.substring(0, hash.length - 10)

        service.verify("password", truncated).shouldBeFalse()
    }

    "verify should work with custom parameters" {
        val algorithm = Argon2id(
            memory = 32768,
            iterations = 2,
            parallelism = 2
        )
        val service = argon2HashingService(algorithm)
        val password = "custom-params"
        val hash = service.hash(password)

        service.verify(password, hash).shouldBeTrue()
    }

    "verify should work across different service instances" {
        val service1 = argon2HashingService()
        val service2 = argon2HashingService()
        val password = "test-password"
        val hash = service1.hash(password)

        service2.verify(password, hash).shouldBeTrue()
    }

    "hash should handle empty string" {
        val service = argon2HashingService()
        val hash = service.hash("")

        hash shouldStartWith "\$argon2id\$"
        service.verify("", hash).shouldBeTrue()
    }

    "hash should handle long passwords" {
        val service = argon2HashingService()
        val longPassword = "a".repeat(1000)
        val hash = service.hash(longPassword)

        service.verify(longPassword, hash).shouldBeTrue()
    }

    "hash should handle Unicode characters" {
        val service = argon2HashingService()
        val unicodePassword = "パスワード🔐密码"
        val hash = service.hash(unicodePassword)

        service.verify(unicodePassword, hash).shouldBeTrue()
    }

    "verify should use constant-time comparison" {
        // This test ensures we're using MessageDigest.isEqual for constant-time comparison
        // While we can't directly test timing, we can verify it handles similar hashes
        val service = argon2HashingService()
        val password = "password"
        val hash = service.hash(password)

        // These should all fail without timing vulnerabilities
        service.verify("passwor", hash).shouldBeFalse()
        service.verify("password1", hash).shouldBeFalse()
        service.verify("Password", hash).shouldBeFalse()
    }

    "Spring Security preset should use correct parameters" {
        val algorithm = Argon2id.springSecurity()
        val service = argon2HashingService(algorithm)
        val hash = service.hash("test")

        hash shouldStartWith "\$argon2id\$v=19\$m=60000,t=10,p=1\$"
    }

    "Keycloak preset should use correct parameters" {
        val algorithm = Argon2id.keycloak()
        val service = argon2HashingService(algorithm)
        val hash = service.hash("test")

        hash shouldStartWith "\$argon2id\$v=19\$m=7168,t=5,p=1\$"
    }

    "OWASP minimum preset should use correct parameters" {
        val algorithm = Argon2id.owaspMinimum()
        val service = argon2HashingService(algorithm)
        val hash = service.hash("test")

        hash shouldStartWith "\$argon2id\$v=19\$m=19456,t=2,p=1\$"
    }

    "balanced preset should be default" {
        val algorithm = Argon2id.balanced()
        val defaultAlgorithm = Argon2id()

        algorithm.memory shouldBe defaultAlgorithm.memory
        algorithm.iterations shouldBe defaultAlgorithm.iterations
        algorithm.parallelism shouldBe defaultAlgorithm.parallelism
    }

    "hash components should be base64 encoded without padding" {
        val service = argon2HashingService()
        val hash = service.hash("test")
        val parts = hash.split('$')

        // Should have 6 parts: ["", "argon2id", "v=19", "m=...,t=...,p=...", "salt", "hash"]
        parts.size shouldBe 6

        // Salt and hash should be valid base64 without padding
        val salt = parts[4]
        val hashPart = parts[5]

        salt.contains('=').shouldBeFalse()
        hashPart.contains('=').shouldBeFalse()

        // Should be decodable
        Base64.getDecoder().decode(salt).isNotEmpty().shouldBeTrue()
        Base64.getDecoder().decode(hashPart).isNotEmpty().shouldBeTrue()
    }
})
