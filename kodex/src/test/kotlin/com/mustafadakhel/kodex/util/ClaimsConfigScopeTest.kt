package com.mustafadakhel.kodex.util

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ClaimsConfigScopeTest : DescribeSpec({

    describe("ClaimsConfig") {
        it("should initialize with null issuer and audience") {
            val config = ClaimsConfig()

            config.issuer.shouldBeNull()
            config.audience.shouldBeNull()
            config.additionalClaims.shouldBeEmpty()
        }

        it("should set issuer") {
            val config = ClaimsConfig()

            config.issuer("https://example.com")

            config.issuer shouldBe "https://example.com"
        }

        it("should set audience") {
            val config = ClaimsConfig()

            config.audience("my-app")

            config.audience shouldBe "my-app"
        }

        it("should set issuer and audience") {
            val config = ClaimsConfig()

            config.issuer("https://auth.example.com")
            config.audience("mobile-app")

            config.issuer shouldBe "https://auth.example.com"
            config.audience shouldBe "mobile-app"
        }

        it("should add single custom claim") {
            val config = ClaimsConfig()

            config.claim("custom_key", "custom_value")

            config.additionalClaims shouldContain ("custom_key" to "custom_value")
        }

        it("should add multiple custom claims") {
            val config = ClaimsConfig()

            config.claim("key1", "value1")
            config.claim("key2", "value2")
            config.claim("key3", "value3")

            config.additionalClaims.size shouldBe 3
            config.additionalClaims shouldContain ("key1" to "value1")
            config.additionalClaims shouldContain ("key2" to "value2")
            config.additionalClaims shouldContain ("key3" to "value3")
        }

        it("should support different value types in claims") {
            val config = ClaimsConfig()

            config.claim("string_claim", "text")
            config.claim("int_claim", 42)
            config.claim("boolean_claim", true)
            config.claim("list_claim", listOf("a", "b", "c"))

            config.additionalClaims.size shouldBe 4
            config.additionalClaims["string_claim"] shouldBe "text"
            config.additionalClaims["int_claim"] shouldBe 42
            config.additionalClaims["boolean_claim"] shouldBe true
            config.additionalClaims["list_claim"] shouldBe listOf("a", "b", "c")
        }

        it("should overwrite claim with same key") {
            val config = ClaimsConfig()

            config.claim("key", "value1")
            config.claim("key", "value2")

            config.additionalClaims.size shouldBe 1
            config.additionalClaims["key"] shouldBe "value2"
        }

        it("should overwrite issuer") {
            val config = ClaimsConfig()

            config.issuer("https://old-issuer.com")
            config.issuer("https://new-issuer.com")

            config.issuer shouldBe "https://new-issuer.com"
        }

        it("should overwrite audience") {
            val config = ClaimsConfig()

            config.audience("old-audience")
            config.audience("new-audience")

            config.audience shouldBe "new-audience"
        }

        it("should combine all configuration methods") {
            val config = ClaimsConfig()

            config.issuer("https://auth.server.com")
            config.audience("web-app")
            config.claim("environment", "production")
            config.claim("version", "1.0.0")

            config.issuer shouldBe "https://auth.server.com"
            config.audience shouldBe "web-app"
            config.additionalClaims.size shouldBe 2
            config.additionalClaims shouldContain ("environment" to "production")
            config.additionalClaims shouldContain ("version" to "1.0.0")
        }

        it("should implement ClaimsConfigScope interface") {
            val config: ClaimsConfigScope = ClaimsConfig()

            config.issuer("test-issuer")
            config.audience("test-audience")
            config.claim("test", "value")

            // Verify interface methods work
            val internalConfig = config as ClaimsConfig
            internalConfig.issuer shouldBe "test-issuer"
            internalConfig.audience shouldBe "test-audience"
            internalConfig.additionalClaims shouldContain ("test" to "value")
        }

        it("should support URL-style issuers") {
            val config = ClaimsConfig()

            config.issuer("https://auth.example.com/realms/my-realm")

            config.issuer shouldBe "https://auth.example.com/realms/my-realm"
        }

        it("should support multiple audiences pattern") {
            val config = ClaimsConfig()

            config.audience("service1,service2,service3")

            config.audience shouldBe "service1,service2,service3"
        }

        it("should handle empty string values") {
            val config = ClaimsConfig()

            config.issuer("")
            config.audience("")
            config.claim("empty", "")

            config.issuer shouldBe ""
            config.audience shouldBe ""
            config.additionalClaims["empty"] shouldBe ""
        }

        it("should handle special characters in claim keys") {
            val config = ClaimsConfig()

            config.claim("custom-claim", "value")
            config.claim("custom_claim_2", "value2")
            config.claim("custom.claim.3", "value3")

            config.additionalClaims.size shouldBe 3
            config.additionalClaims shouldContain ("custom-claim" to "value")
            config.additionalClaims shouldContain ("custom_claim_2" to "value2")
            config.additionalClaims shouldContain ("custom.claim.3" to "value3")
        }

        it("should support numeric claim values") {
            val config = ClaimsConfig()

            config.claim("int_value", 100)
            config.claim("long_value", 1000L)
            config.claim("double_value", 3.14)

            config.additionalClaims["int_value"] shouldBe 100
            config.additionalClaims["long_value"] shouldBe 1000L
            config.additionalClaims["double_value"] shouldBe 3.14
        }

        it("should support collection claim values") {
            val config = ClaimsConfig()

            config.claim("list", listOf("a", "b"))
            config.claim("set", setOf(1, 2, 3))
            config.claim("map", mapOf("key" to "value"))

            config.additionalClaims.size shouldBe 3
            config.additionalClaims["list"] shouldBe listOf("a", "b")
            config.additionalClaims["set"] shouldBe setOf(1, 2, 3)
            config.additionalClaims["map"] shouldBe mapOf("key" to "value")
        }
    }
})
