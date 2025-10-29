package com.mustafadakhel.kodex.util

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class ExtensionsTest : DescribeSpec({

    describe("String.toUuidOrNull") {
        it("should parse valid UUID string") {
            val uuidString = "550e8400-e29b-41d4-a716-446655440000"
            val result = uuidString.toUuidOrNull()

            result.shouldNotBeNull()
            result.toString() shouldBe uuidString
        }

        it("should return null for invalid UUID string") {
            val invalidString = "not-a-uuid"
            val result = invalidString.toUuidOrNull()

            result.shouldBeNull()
        }

        it("should return null for empty string") {
            val result = "".toUuidOrNull()
            result.shouldBeNull()
        }

        it("should return null for malformed UUID") {
            val result = "550e8400-e29b-41d4-a716".toUuidOrNull()
            result.shouldBeNull()
        }

        it("should handle UUID with uppercase letters") {
            val uuidString = "550E8400-E29B-41D4-A716-446655440000"
            val result = uuidString.toUuidOrNull()

            result.shouldNotBeNull()
            result.toString() shouldBe uuidString.lowercase()
        }

        it("should return null for UUID-like string with invalid characters") {
            val result = "550e8400-e29b-41d4-a716-44665544000g".toUuidOrNull()
            result.shouldBeNull()
        }

        it("should handle random UUID strings") {
            val uuid = UUID.randomUUID()
            val result = uuid.toString().toUuidOrNull()

            result shouldBe uuid
        }

        it("should return null for special characters") {
            val result = "!@#$%^&*()".toUuidOrNull()
            result.shouldBeNull()
        }

        it("should return null for numeric string") {
            val result = "12345678901234567890".toUuidOrNull()
            result.shouldBeNull()
        }

        it("should return null for whitespace string") {
            val result = "   ".toUuidOrNull()
            result.shouldBeNull()
        }

        it("should handle mixed case UUID") {
            val result = "550e8400-E29B-41d4-A716-446655440000".toUuidOrNull()
            result.shouldNotBeNull()
        }
    }
})
