package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class DefaultValidationHookTest : FunSpec({

    val hook = DefaultValidationHook()

    context("Email Validation") {
        test("should accept valid email addresses") {
            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }

            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = "test.user+tag@sub.domain.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
        }

        test("should reject blank email") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "cannot be blank"
        }

        test("should reject email without @") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "notanemail.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "exactly one @ symbol"
        }

        test("should reject email with multiple @ symbols") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "exactly one @ symbol"
        }

        test("should reject email without domain") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "domain cannot be empty"
        }

        test("should reject email without local part") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "local part cannot be empty"
        }

        test("should reject email domain without dot") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@localhost",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "must contain a dot"
        }

        test("should reject too long email") {
            val longEmail = "a".repeat(310) + "@example.com"  // 310 + 12 = 322 chars (over 320 limit)
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = longEmail,
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "too long"
        }
    }

    context("Phone Validation") {
        test("should accept valid E.164 phone numbers") {
            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = null,
                    phone = "+14155552671",
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }

            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = null,
                    phone = "+442012345678",
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
        }

        test("should reject phone without + prefix") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = null,
                    phone = "14155552671",
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "E.164 format"
        }

        test("should reject phone with invalid format") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = null,
                    phone = "+1-415-555-2671",
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "E.164 format"
        }

        test("should reject blank phone") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = null,
                    phone = "",
                    password = "Test1234",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "cannot be blank"
        }
    }

    context("Password Validation") {
        test("should accept strong passwords") {
            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "StrongPass123",
                    customAttributes = null,
                    profile = null
                )
            }
        }

        test("should reject password shorter than 8 characters") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "Short1",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "at least 8 characters"
        }

        test("should reject password longer than 128 characters") {
            val longPassword = "A1" + "a".repeat(130)
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = longPassword,
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "too long"
        }

        test("should reject password without letters") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "12345678",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "at least one letter and one number"
        }

        test("should reject password without numbers") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "abcdefgh",
                    customAttributes = null,
                    profile = null
                )
            }
            exception.message shouldContain "at least one letter and one number"
        }
    }

    context("Profile Validation") {
        test("should accept clean profile data") {
            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = UserProfile(
                        firstName = "John",
                        lastName = "Doe",
                        address = "123 Main St",
                        profilePicture = "https://example.com/pic.jpg"
                    )
                )
            }
        }

        test("should reject profile with script tags in firstName") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = UserProfile(
                        firstName = "<script>alert('xss')</script>",
                        lastName = "Doe",
                        address = null,
                        profilePicture = null
                    )
                )
            }
            exception.message shouldContain "malicious content"
        }

        test("should reject profile with javascript: in profilePicture") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = UserProfile(
                        firstName = "John",
                        lastName = "Doe",
                        address = null,
                        profilePicture = "javascript:alert('xss')"
                    )
                )
            }
            exception.message shouldContain "malicious content"
        }

        test("should accept null profile fields") {
            shouldNotThrowAny {
                hook.beforeUserCreate(
                    email = "user@example.com",
                    phone = null,
                    password = "Test1234",
                    customAttributes = null,
                    profile = UserProfile(
                        firstName = null,
                        lastName = null,
                        address = null,
                        profilePicture = null
                    )
                )
            }
        }
    }

    context("beforeUserUpdate Validation") {
        val userId = UUID.randomUUID()

        test("should validate email on update") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserUpdate(
                    userId = userId,
                    email = "invalid-email",
                    phone = null
                )
            }
            exception.message shouldContain "exactly one @ symbol"
        }

        test("should validate phone on update") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeUserUpdate(
                    userId = userId,
                    email = null,
                    phone = "1234567890"
                )
            }
            exception.message shouldContain "E.164 format"
        }

        test("should allow null email and phone (no update)") {
            shouldNotThrowAny {
                hook.beforeUserUpdate(
                    userId = userId,
                    email = null,
                    phone = null
                )
            }
        }
    }

    context("beforeProfileUpdate Validation") {
        val userId = UUID.randomUUID()

        test("should validate profile fields on update") {
            val exception = shouldThrow<KodexThrowable.Validation.ValidationFailed> {
                hook.beforeProfileUpdate(
                    userId = userId,
                    firstName = "<script>xss</script>",
                    lastName = null,
                    address = null,
                    profilePicture = null
                )
            }
            exception.message shouldContain "malicious content"
        }

        test("should allow clean profile update") {
            shouldNotThrowAny {
                hook.beforeProfileUpdate(
                    userId = userId,
                    firstName = "John",
                    lastName = "Doe",
                    address = "123 Main St",
                    profilePicture = "pic.jpg"
                )
            }
        }
    }
})
