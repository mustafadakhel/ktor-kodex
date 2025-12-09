package com.mustafadakhel.kodex.tokens.token

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class TokenGeneratorTest : StringSpec({

    "generate with UUIDv4Format returns UUID instance" {
        val uuid = TokenGenerator.generate(UUIDv4Format)
        uuid.shouldBeInstanceOf<UUID>()
    }

    "generate with UUIDv7Format returns UUID instance" {
        val uuid = TokenGenerator.generate(UUIDv7Format)
        uuid.shouldBeInstanceOf<UUID>()
    }

    "generate with HexFormat returns hex string" {
        val token = TokenGenerator.generate(HexFormat())
        token shouldHaveLength 32
        token.all { it in '0'..'9' || it in 'a'..'f' }.shouldBeTrue()
    }

    "generate with AlphanumericFormat returns alphanumeric string" {
        val token = TokenGenerator.generate(AlphanumericFormat())
        token shouldHaveLength 32
        token.all { it in '0'..'9' || it in 'a'..'z' }.shouldBeTrue()
    }

    "generate with NumericFormat returns numeric string" {
        val code = TokenGenerator.generate(NumericFormat())
        code shouldHaveLength 6
        code.all { it in '0'..'9' }.shouldBeTrue()
    }

    "generate with Base64UrlFormat returns URL-safe string" {
        val token = TokenGenerator.generate(Base64UrlFormat())
        token.contains('+').shouldBeFalse()
        token.contains('/').shouldBeFalse()
        token.contains('=').shouldBeFalse()
        token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }.shouldBeTrue()
    }

    "UUIDv4Format produces unique UUIDs" {
        val tokens = (1..1000).map { TokenGenerator.generate(UUIDv4Format) }.toSet()
        tokens.size shouldBeExactly 1000
    }

    "UUIDv7Format produces unique UUIDs" {
        val tokens = (1..1000).map { TokenGenerator.generate(UUIDv7Format) }.toSet()
        tokens.size shouldBeExactly 1000
    }

    "UUIDv7Format generates time-ordered UUIDs" {
        val uuid1 = TokenGenerator.generate(UUIDv7Format)
        Thread.sleep(5)
        val uuid2 = TokenGenerator.generate(UUIDv7Format)

        // UUIDv7 embeds timestamp in most significant bits, so lexicographic sort = time sort
        (uuid1.toString() < uuid2.toString()).shouldBeTrue()
    }

    "HexFormat generates 32-character hex string by default" {
        val token = TokenGenerator.generate(HexFormat())

        token shouldHaveLength 32
        token.all { it in '0'..'9' || it in 'a'..'f' }.shouldBeTrue()
    }

    "HexFormat generates custom length tokens" {
        val token16 = TokenGenerator.generate(HexFormat(16))
        val token64 = TokenGenerator.generate(HexFormat(64))

        token16 shouldHaveLength 16
        token64 shouldHaveLength 64
    }

    "HexFormat generates unique tokens" {
        val tokens = (1..1000).map { TokenGenerator.generate(HexFormat()) }.toSet()
        tokens.size shouldBeExactly 1000
    }

    "HexFormat requires even length" {
        shouldThrow<IllegalArgumentException> {
            HexFormat(17)
        }
    }

    "HexFormat requires positive length" {
        shouldThrow<IllegalArgumentException> {
            HexFormat(0)
        }
    }

    "AlphanumericFormat generates 32-character token by default" {
        val token = TokenGenerator.generate(AlphanumericFormat())

        token shouldHaveLength 32
        token.all { it in '0'..'9' || it in 'a'..'z' }.shouldBeTrue()
    }

    "AlphanumericFormat generates custom length tokens" {
        val token8 = TokenGenerator.generate(AlphanumericFormat(8))
        val token64 = TokenGenerator.generate(AlphanumericFormat(64))

        token8 shouldHaveLength 8
        token64 shouldHaveLength 64
    }

    "AlphanumericFormat lowercase mode excludes uppercase" {
        val tokens = (1..100).map { TokenGenerator.generate(AlphanumericFormat(100, uppercase = false)) }

        tokens.forEach { token ->
            token.any { it in 'A'..'Z' }.shouldBeFalse()
        }
    }

    "AlphanumericFormat uppercase mode includes uppercase" {
        val tokens = (1..100).map { TokenGenerator.generate(AlphanumericFormat(100, uppercase = true)) }

        // At least some tokens should have uppercase (statistically certain)
        tokens.any { token -> token.any { it in 'A'..'Z' } }.shouldBeTrue()
    }

    "AlphanumericFormat generates unique tokens" {
        val tokens = (1..1000).map { TokenGenerator.generate(AlphanumericFormat()) }.toSet()
        tokens.size shouldBeExactly 1000
    }

    "AlphanumericFormat requires positive length" {
        shouldThrow<IllegalArgumentException> {
            AlphanumericFormat(0)
        }
    }

    "NumericFormat generates 6-digit code by default" {
        val code = TokenGenerator.generate(NumericFormat())

        code shouldHaveLength 6
        code.all { it in '0'..'9' }.shouldBeTrue()
    }

    "NumericFormat generates custom length codes" {
        val code4 = TokenGenerator.generate(NumericFormat(4))
        val code8 = TokenGenerator.generate(NumericFormat(8))

        code4 shouldHaveLength 4
        code8 shouldHaveLength 8
    }

    "NumericFormat pads with leading zeros" {
        repeat(50) {
            val code = TokenGenerator.generate(NumericFormat(6))
            code shouldHaveLength 6  // Always 6 chars even if starts with 0
        }
    }

    "NumericFormat generates different codes" {
        val codes = (1..100).map { TokenGenerator.generate(NumericFormat()) }.toSet()
        codes.size shouldBeGreaterThan 90  // Statistical: very likely to be unique
    }

    "NumericFormat enforces length limits" {
        shouldThrow<IllegalArgumentException> {
            NumericFormat(0)
        }

        shouldThrow<IllegalArgumentException> {
            NumericFormat(19)
        }
    }

    "Base64UrlFormat generates URL-safe tokens" {
        val token = TokenGenerator.generate(Base64UrlFormat())

        // Base64url should not contain + or / or =
        token.contains('+').shouldBeFalse()
        token.contains('/').shouldBeFalse()
        token.contains('=').shouldBeFalse()

        // Should contain URL-safe characters
        token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }.shouldBeTrue()
    }

    "Base64UrlFormat generates unique tokens" {
        val tokens = (1..1000).map { TokenGenerator.generate(Base64UrlFormat()) }.toSet()
        tokens.size shouldBeExactly 1000
    }

    "Base64UrlFormat supports custom byte length" {
        val token16 = TokenGenerator.generate(Base64UrlFormat(16))
        val token64 = TokenGenerator.generate(Base64UrlFormat(64))

        // Base64url encoding: every 3 bytes -> 4 chars (without padding)
        token16.length shouldBeGreaterThan 16
        token64.length shouldBeGreaterThan 64
    }

    "Base64UrlFormat requires positive byte length" {
        shouldThrow<IllegalArgumentException> {
            Base64UrlFormat(0)
        }
    }

    "toHexString converts UUID to 32-char hex" {
        val uuid = TokenGenerator.generate(UUIDv4Format)
        val hex = uuid.toHexString()

        hex shouldHaveLength 32
        hex.contains('-').shouldBeFalse()
        hex.all { it in '0'..'9' || it in 'a'..'f' }.shouldBeTrue()
    }

    "toHexString is deterministic" {
        val uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val hex = uuid.toHexString()

        hex shouldBe "550e8400e29b41d4a716446655440000"
    }

    "toBase64Url converts UUID to 22-char base64url" {
        val uuid = TokenGenerator.generate(UUIDv4Format)
        val base64 = uuid.toBase64Url()

        base64 shouldHaveLength 22  // 128 bits / 6 bits per char = 21.33, rounded up
        base64.contains('=').shouldBeFalse()  // No padding
    }

    "toBase64Url is more compact than toHexString" {
        val uuid = TokenGenerator.generate(UUIDv4Format)

        uuid.toBase64Url().length shouldBe 22
        uuid.toHexString().length shouldBe 32
    }

    "TokenGenerator is thread-safe" {
        val tokens = mutableSetOf<UUID>()
        val threads = (1..10).map {
            Thread {
                repeat(100) {
                    synchronized(tokens) {
                        tokens.add(TokenGenerator.generate(UUIDv4Format))
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        tokens.size shouldBeExactly 1000
    }

    "custom TokenFormat can be created and used" {
        val customFormat = object : TokenFormat<String> {
            override fun generate(random: java.security.SecureRandom): String {
                return "CUSTOM-${random.nextInt(1000)}"
            }
        }

        val token = TokenGenerator.generate(customFormat)
        token.startsWith("CUSTOM-").shouldBeTrue()
    }
})
