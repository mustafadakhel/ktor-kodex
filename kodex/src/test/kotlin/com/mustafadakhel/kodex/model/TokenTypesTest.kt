package com.mustafadakhel.kodex.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TokenTypesTest : DescribeSpec({

    describe("TokenType.AccessToken") {
        it("should have correct name") {
            TokenType.AccessToken.name shouldBe "access"
        }

        it("should have correct claim") {
            TokenType.AccessToken.claim shouldBe Claim.TokenType.AccessToken
        }

        it("should be a singleton") {
            (TokenType.AccessToken === TokenType.AccessToken) shouldBe true
        }
    }

    describe("TokenType.RefreshToken") {
        it("should have correct name") {
            TokenType.RefreshToken.name shouldBe "refresh"
        }

        it("should have correct claim") {
            TokenType.RefreshToken.claim shouldBe Claim.TokenType.RefreshToken
        }

        it("should be a singleton") {
            (TokenType.RefreshToken === TokenType.RefreshToken) shouldBe true
        }
    }

    describe("TokenType companion functions") {
        describe("fromClaim") {
            it("should convert AccessToken claim") {
                val result = TokenType.fromClaim(Claim.TokenType.AccessToken)
                result shouldBe TokenType.AccessToken
            }

            it("should convert RefreshToken claim") {
                val result = TokenType.fromClaim(Claim.TokenType.RefreshToken)
                result shouldBe TokenType.RefreshToken
            }

            it("should return null for Unknown claim") {
                val result = TokenType.fromClaim(Claim.TokenType.Unknown("custom"))
                result.shouldBeNull()
            }
        }

        describe("fromString") {
            it("should convert 'access' string") {
                val result = TokenType.fromString("access")
                result shouldBe TokenType.AccessToken
            }

            it("should convert 'refresh' string") {
                val result = TokenType.fromString("refresh")
                result shouldBe TokenType.RefreshToken
            }

            it("should throw for unknown string") {
                val exception = shouldThrow<IllegalArgumentException> {
                    TokenType.fromString("unknown")
                }
                exception.message shouldBe "Unknown token type: unknown"
            }

            it("should throw for null string") {
                val exception = shouldThrow<IllegalArgumentException> {
                    TokenType.fromString(null)
                }
                exception.message shouldBe "Unknown token type: null"
            }

            it("should throw for empty string") {
                val exception = shouldThrow<IllegalArgumentException> {
                    TokenType.fromString("")
                }
                exception.message shouldBe "Unknown token type: "
            }
        }
    }

    describe("TokenValidity") {
        it("should have default access duration of 2 hours") {
            val validity = TokenValidity()
            validity.access shouldBe 2.hours
        }

        it("should have default refresh duration of 90 days") {
            val validity = TokenValidity()
            validity.refresh shouldBe 90.days
        }

        it("should allow custom access duration") {
            val validity = TokenValidity(access = 15.minutes)
            validity.access shouldBe 15.minutes
        }

        it("should allow custom refresh duration") {
            val validity = TokenValidity(refresh = 30.days)
            validity.refresh shouldBe 30.days
        }

        it("should allow custom durations for both") {
            val validity = TokenValidity(access = 1.hours, refresh = 7.days)
            validity.access shouldBe 1.hours
            validity.refresh shouldBe 7.days
        }

        it("should have Default constant with default values") {
            val validity = TokenValidity.Default
            validity.access shouldBe 2.hours
            validity.refresh shouldBe 90.days
        }

        it("should support data class equality") {
            val validity1 = TokenValidity(access = 2.hours, refresh = 90.days)
            val validity2 = TokenValidity(access = 2.hours, refresh = 90.days)

            (validity1 == validity2) shouldBe true
        }

        it("should support data class copy") {
            val original = TokenValidity(access = 1.hours, refresh = 30.days)
            val copy = original.copy(access = 2.hours)

            copy.access shouldBe 2.hours
            copy.refresh shouldBe 30.days
        }

        it("should have different validity for different access durations") {
            val validity1 = TokenValidity(access = 1.hours)
            val validity2 = TokenValidity(access = 2.hours)

            (validity1 == validity2) shouldBe false
        }

        it("should have different validity for different refresh durations") {
            val validity1 = TokenValidity(refresh = 30.days)
            val validity2 = TokenValidity(refresh = 90.days)

            (validity1 == validity2) shouldBe false
        }
    }
})
