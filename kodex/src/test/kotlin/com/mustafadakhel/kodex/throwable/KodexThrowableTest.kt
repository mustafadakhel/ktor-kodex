package com.mustafadakhel.kodex.throwable

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
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
})
