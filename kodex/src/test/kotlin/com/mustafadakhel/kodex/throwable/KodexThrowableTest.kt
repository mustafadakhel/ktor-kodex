package com.mustafadakhel.kodex.throwable

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

class KodexThrowableTest : DescribeSpec({

    describe("KodexThrowable") {
        describe("EmailAlreadyExists") {
            it("should have correct message") {
                val exception = KodexThrowable.EmailAlreadyExists()
                exception.message shouldBe "Email already exists"
            }

            it("should support cause") {
                val cause = RuntimeException("Database error")
                val exception = KodexThrowable.EmailAlreadyExists(cause)
                exception.cause shouldBe cause
            }
        }

        describe("PhoneAlreadyExists") {
            it("should have correct message") {
                val exception = KodexThrowable.PhoneAlreadyExists()
                exception.message shouldBe "Phone number already exists"
            }

            it("should support cause") {
                val cause = RuntimeException("Database error")
                val exception = KodexThrowable.PhoneAlreadyExists(cause)
                exception.cause shouldBe cause
            }
        }

        describe("Unknown") {
            it("should support custom message") {
                val exception = KodexThrowable.Unknown("Custom error")
                exception.message shouldBe "Custom error"
            }

            it("should support cause") {
                val cause = RuntimeException("Root cause")
                val exception = KodexThrowable.Unknown("Error", cause)
                exception.cause shouldBe cause
            }

            it("should allow null message") {
                val exception = KodexThrowable.Unknown()
                exception.message shouldBe null
            }
        }

        describe("UserNotFound") {
            it("should support custom message") {
                val exception = KodexThrowable.UserNotFound("User with ID 123 not found")
                exception.message shouldBe "User with ID 123 not found"
            }

            it("should allow null message") {
                val exception = KodexThrowable.UserNotFound()
                exception.message shouldBe null
            }
        }

        describe("UserUpdateFailed") {
            it("should include user ID in message") {
                val userId = UUID.randomUUID()
                val exception = KodexThrowable.UserUpdateFailed(userId)
                exception.message shouldContain userId.toString()
                exception.message shouldContain "Failed to update user"
            }
        }

        describe("ProfileNotFound") {
            it("should include user ID in message") {
                val userId = UUID.randomUUID()
                val exception = KodexThrowable.ProfileNotFound(userId)
                exception.message shouldContain userId.toString()
                exception.message shouldContain "Profile not found"
            }
        }

        describe("RoleNotFound") {
            it("should include role name in message") {
                val exception = KodexThrowable.RoleNotFound("admin")
                exception.message shouldBe "Role not found: admin"
                exception.roleName shouldBe "admin"
            }

            it("should support cause") {
                val cause = RuntimeException("Database error")
                val exception = KodexThrowable.RoleNotFound("admin", cause)
                exception.cause shouldBe cause
            }
        }
    }

    describe("Database exceptions") {
        describe("Unknown") {
            it("should support custom message") {
                val exception = KodexThrowable.Database.Unknown("Connection failed")
                exception.message shouldBe "Connection failed"
            }

            it("should support cause") {
                val cause = RuntimeException("JDBC error")
                val exception = KodexThrowable.Database.Unknown("Error", cause)
                exception.cause shouldBe cause
            }
        }
    }

    describe("Authorization exceptions") {
        describe("InvalidCredentials") {
            it("should have correct message") {
                val exception = KodexThrowable.Authorization.InvalidCredentials
                exception.message shouldBe "Invalid credentials"
            }

            it("should be a singleton") {
                val ex1 = KodexThrowable.Authorization.InvalidCredentials
                val ex2 = KodexThrowable.Authorization.InvalidCredentials
                (ex1 === ex2) shouldBe true
            }
        }

        describe("UserRoleNotFound") {
            it("should have correct message") {
                val exception = KodexThrowable.Authorization.UserRoleNotFound
                exception.message shouldBe "User role not found"
            }
        }

        describe("UserHasNoRoles") {
            it("should have correct message") {
                val exception = KodexThrowable.Authorization.UserHasNoRoles
                exception.message shouldBe "User has no roles assigned"
            }
        }

        describe("UnverifiedAccount") {
            it("should have correct message") {
                val exception = KodexThrowable.Authorization.UnverifiedAccount
                exception.message shouldBe "Account not verified"
            }
        }

        describe("AccountLocked") {
            it("should include locked until and reason in message") {
                val lockedUntil = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val exception = KodexThrowable.Authorization.AccountLocked(
                    lockedUntil = lockedUntil,
                    reason = "Too many failed login attempts"
                )
                exception.message shouldContain lockedUntil.toString()
                exception.message shouldContain "Too many failed login attempts"
                exception.message shouldContain "Account is locked"
            }
        }

        describe("InvalidToken") {
            it("should have base message without additional info") {
                val exception = KodexThrowable.Authorization.InvalidToken()
                exception.message shouldBe "Invalid token: null"
            }

            it("should include additional info in message") {
                val exception = KodexThrowable.Authorization.InvalidToken("Expired")
                exception.message shouldBe "Invalid token: Expired"
            }
        }

        describe("SuspiciousToken") {
            it("should have base message without additional info") {
                val exception = KodexThrowable.Authorization.SuspiciousToken()
                exception.message shouldBe "Suspicious token: null"
            }

            it("should include additional info in message") {
                val exception = KodexThrowable.Authorization.SuspiciousToken("IP mismatch")
                exception.message shouldBe "Suspicious token: IP mismatch"
            }
        }

        describe("TokenReplayDetected") {
            it("should include token family and original token ID in message") {
                val tokenFamily = UUID.randomUUID()
                val originalTokenId = UUID.randomUUID()
                val exception = KodexThrowable.Authorization.TokenReplayDetected(
                    tokenFamily = tokenFamily,
                    originalTokenId = originalTokenId
                )
                exception.message shouldContain tokenFamily.toString()
                exception.message shouldContain "replay attack detected"
                exception.message shouldContain "revoked"
            }
        }
    }

    describe("Validation exceptions") {
        describe("ValidationFailed") {
            it("should use provided message") {
                val exception = KodexThrowable.Validation.ValidationFailed("Field is required")
                exception.message shouldBe "Field is required"
            }
        }

        describe("InvalidEmail") {
            it("should include email and errors in message") {
                val exception = KodexThrowable.Validation.InvalidEmail(
                    email = "invalid@",
                    errors = listOf("Missing domain", "Invalid format")
                )
                exception.message shouldContain "invalid@"
                exception.message shouldContain "Missing domain"
                exception.message shouldContain "Invalid format"
                exception.email shouldBe "invalid@"
                exception.errors shouldBe listOf("Missing domain", "Invalid format")
            }
        }

        describe("InvalidPhone") {
            it("should include phone and errors in message") {
                val exception = KodexThrowable.Validation.InvalidPhone(
                    phone = "123",
                    errors = listOf("Too short", "Invalid country code")
                )
                exception.message shouldContain "123"
                exception.message shouldContain "Too short"
                exception.message shouldContain "Invalid country code"
                exception.phone shouldBe "123"
            }
        }

        describe("WeakPassword") {
            it("should include score and feedback in message") {
                val exception = KodexThrowable.Validation.WeakPassword(
                    score = 2,
                    feedback = listOf("Add uppercase", "Add numbers", "Increase length")
                )
                exception.message shouldContain "score: 2"
                exception.message shouldContain "Add uppercase"
                exception.message shouldContain "Add numbers"
                exception.message shouldContain "Increase length"
                exception.score shouldBe 2
            }
        }

        describe("InvalidCustomAttribute") {
            it("should include key and errors in message") {
                val exception = KodexThrowable.Validation.InvalidCustomAttribute(
                    key = "department",
                    errors = listOf("Invalid value", "Must be one of: IT, HR, Sales")
                )
                exception.message shouldContain "department"
                exception.message shouldContain "Invalid value"
                exception.message shouldContain "Must be one of"
                exception.key shouldBe "department"
            }
        }

        describe("InvalidInput") {
            it("should include field and errors in message") {
                val exception = KodexThrowable.Validation.InvalidInput(
                    field = "age",
                    errors = listOf("Must be positive", "Must be integer")
                )
                exception.message shouldContain "age"
                exception.message shouldContain "Must be positive"
                exception.message shouldContain "Must be integer"
                exception.field shouldBe "age"
            }
        }
    }
})
