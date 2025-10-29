package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID

class UpdateResultTest : DescribeSpec({

    describe("UpdateResult.Success") {
        it("should create success result with user and changes") {
            val user = createTestFullUser()
            val changes = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = mapOf("email" to FieldChange("email", "old@test.com", "new@test.com"))
            )

            val result = UpdateResult.Success(user, changes)

            result.user shouldBe user
            result.changes shouldBe changes
        }

        it("should return true for hasChanges when fields changed") {
            val changes = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = mapOf("email" to FieldChange("email", "old", "new"))
            )
            val result = UpdateResult.Success(createTestFullUser(), changes)

            result.hasChanges() shouldBe true
        }

        it("should return false for hasChanges when no fields changed") {
            val changes = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = emptyMap()
            )
            val result = UpdateResult.Success(createTestFullUser(), changes)

            result.hasChanges() shouldBe false
        }
    }

    describe("UpdateResult.Failure.NotFound") {
        it("should include userId") {
            val userId = UUID.randomUUID()
            val failure = UpdateResult.Failure.NotFound(userId)

            failure.userId shouldBe userId
        }

        it("should be a Failure") {
            val failure = UpdateResult.Failure.NotFound(UUID.randomUUID())
            failure.shouldBeInstanceOf<UpdateResult.Failure>()
        }
    }

    describe("UpdateResult.Failure.ValidationFailed") {
        it("should include validation errors") {
            val errors = listOf(
                ValidationError("email", "Invalid format", "INVALID_EMAIL"),
                ValidationError("phone", "Too short", "TOO_SHORT")
            )
            val failure = UpdateResult.Failure.ValidationFailed(errors)

            failure.errors shouldBe errors
        }

        it("should support multiple errors") {
            val errors = listOf(
                ValidationError("field1", "error1"),
                ValidationError("field2", "error2"),
                ValidationError("field3", "error3")
            )
            val failure = UpdateResult.Failure.ValidationFailed(errors)

            failure.errors.size shouldBe 3
        }
    }

    describe("UpdateResult.Failure.ConstraintViolation") {
        it("should include field and reason") {
            val failure = UpdateResult.Failure.ConstraintViolation(
                field = "email",
                reason = "Email already exists"
            )

            failure.field shouldBe "email"
            failure.reason shouldBe "Email already exists"
        }
    }

    describe("UpdateResult.Failure.Unknown") {
        it("should include message") {
            val failure = UpdateResult.Failure.Unknown("Database connection failed")

            failure.message shouldBe "Database connection failed"
            failure.cause.shouldBeNull()
        }

        it("should include cause when provided") {
            val cause = RuntimeException("Root cause")
            val failure = UpdateResult.Failure.Unknown("Failed", cause)

            failure.message shouldBe "Failed"
            failure.cause shouldBe cause
        }

        it("should allow null cause") {
            val failure = UpdateResult.Failure.Unknown("Error", null)
            failure.cause.shouldBeNull()
        }
    }

    describe("ValidationError") {
        it("should create error with field and message") {
            val error = ValidationError("email", "Invalid format")

            error.field shouldBe "email"
            error.message shouldBe "Invalid format"
            error.code.shouldBeNull()
        }

        it("should support error code") {
            val error = ValidationError("phone", "Too short", "TOO_SHORT")

            error.field shouldBe "phone"
            error.message shouldBe "Too short"
            error.code shouldBe "TOO_SHORT"
        }

        it("should allow null code") {
            val error = ValidationError("field", "message", null)
            error.code.shouldBeNull()
        }
    }

    describe("ChangeSet") {
        it("should track changed fields") {
            val changes = mapOf(
                "email" to FieldChange("email", "old@test.com", "new@test.com"),
                "phone" to FieldChange("phone", "+1234", "+5678")
            )
            val changeSet = ChangeSet(Clock.System.now(), changes)

            changeSet.changedFields shouldBe changes
        }

        it("should check if field was changed") {
            val changeSet = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = mapOf("email" to FieldChange("email", "old", "new"))
            )

            changeSet.hasFieldChange("email") shouldBe true
            changeSet.hasFieldChange("phone") shouldBe false
        }

        it("should get field change") {
            val fieldChange = FieldChange("email", "old", "new")
            val changeSet = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = mapOf("email" to fieldChange)
            )

            changeSet.getFieldChange("email") shouldBe fieldChange
            changeSet.getFieldChange("phone").shouldBeNull()
        }

        it("should return list of changed field names") {
            val changeSet = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = mapOf(
                    "email" to FieldChange("email", "a", "b"),
                    "phone" to FieldChange("phone", "c", "d"),
                    "name" to FieldChange("name", "e", "f")
                )
            )

            val fieldNames = changeSet.changedFieldNames()
            fieldNames.size shouldBe 3
            fieldNames shouldContain "email"
            fieldNames shouldContain "phone"
            fieldNames shouldContain "name"
        }

        it("should return empty list when no changes") {
            val changeSet = ChangeSet(
                timestamp = Clock.System.now(),
                changedFields = emptyMap()
            )

            changeSet.changedFieldNames().shouldBeEmpty()
        }
    }

    describe("FieldChange") {
        it("should create field change with old and new values") {
            val change = FieldChange("email", "old@test.com", "new@test.com")

            change.fieldName shouldBe "email"
            change.oldValue shouldBe "old@test.com"
            change.newValue shouldBe "new@test.com"
        }

        it("should return true for hasChanged when values differ") {
            val change = FieldChange("field", "old", "new")
            change.hasChanged() shouldBe true
        }

        it("should return false for hasChanged when values are same") {
            val change = FieldChange("field", "same", "same")
            change.hasChanged() shouldBe false
        }

        it("should handle null values") {
            val change = FieldChange("field", null, "new")
            change.hasChanged() shouldBe true
        }

        it("should handle both values null") {
            val change = FieldChange("field", null, null)
            change.hasChanged() shouldBe false
        }

        it("should have readable toString") {
            val change = FieldChange("email", "old@test.com", "new@test.com")
            val string = change.toString()

            string shouldContain "email"
            string shouldContain "old@test.com"
            string shouldContain "new@test.com"
        }
    }

    describe("Extension functions") {
        describe("toResult") {
            it("should convert Success to success Result") {
                val user = createTestFullUser()
                val success = UpdateResult.Success(
                    user = user,
                    changes = ChangeSet(Clock.System.now(), emptyMap())
                )

                val result = success.toResult()
                result.isSuccess shouldBe true
                result.getOrNull() shouldBe user
            }

            it("should convert NotFound to failure Result") {
                val userId = UUID.randomUUID()
                val failure = UpdateResult.Failure.NotFound(userId)

                val result = failure.toResult()
                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldContain userId.toString()
            }

            it("should convert ValidationFailed to failure Result") {
                val errors = listOf(ValidationError("email", "Invalid"))
                val failure = UpdateResult.Failure.ValidationFailed(errors)

                val result = failure.toResult()
                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldContain "Validation failed"
            }

            it("should convert ConstraintViolation to failure Result") {
                val failure = UpdateResult.Failure.ConstraintViolation("email", "Already exists")

                val result = failure.toResult()
                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldContain "email"
                result.exceptionOrNull()?.message shouldContain "Already exists"
            }

            it("should convert Unknown to failure Result with cause") {
                val cause = RuntimeException("Root cause")
                val failure = UpdateResult.Failure.Unknown("Error", cause)

                val result = failure.toResult()
                result.isFailure shouldBe true
                result.exceptionOrNull() shouldBe cause
            }

            it("should convert Unknown to failure Result without cause") {
                val failure = UpdateResult.Failure.Unknown("Error message")

                val result = failure.toResult()
                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldBe "Error message"
            }
        }

        describe("userOrThrow") {
            it("should return user from Success") {
                val user = createTestFullUser()
                val success = UpdateResult.Success(
                    user = user,
                    changes = ChangeSet(Clock.System.now(), emptyMap())
                )

                success.userOrThrow() shouldBe user
            }

            it("should throw for NotFound") {
                val userId = UUID.randomUUID()
                val failure = UpdateResult.Failure.NotFound(userId)

                val exception = shouldThrow<Exception> {
                    failure.userOrThrow()
                }
                exception.message shouldContain userId.toString()
            }

            it("should throw for ValidationFailed") {
                val errors = listOf(ValidationError("field", "error"))
                val failure = UpdateResult.Failure.ValidationFailed(errors)

                val exception = shouldThrow<Exception> {
                    failure.userOrThrow()
                }
                exception.message shouldContain "Validation failed"
            }

            it("should throw for ConstraintViolation") {
                val failure = UpdateResult.Failure.ConstraintViolation("email", "Duplicate")

                val exception = shouldThrow<Exception> {
                    failure.userOrThrow()
                }
                exception.message shouldContain "email"
                exception.message shouldContain "Duplicate"
            }

            it("should throw cause for Unknown when cause present") {
                val cause = RuntimeException("Root cause")
                val failure = UpdateResult.Failure.Unknown("Error", cause)

                val exception = shouldThrow<RuntimeException> {
                    failure.userOrThrow()
                }
                exception shouldBe cause
            }

            it("should throw exception for Unknown when no cause") {
                val failure = UpdateResult.Failure.Unknown("Error message")

                val exception = shouldThrow<Exception> {
                    failure.userOrThrow()
                }
                exception.message shouldBe "Error message"
            }
        }
    }
})

private fun createTestFullUser(): FullUser {
    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
    return FullUser(
        id = UUID.randomUUID(),
        phoneNumber = null,
        email = "test@example.com",
        createdAt = now,
        updatedAt = now,
        isVerified = true,
        lastLoggedIn = now,
        roles = listOf(Role(name = "user", description = "User role")),
        profile = UserProfile(
            firstName = "Test",
            lastName = "User",
            address = null,
            profilePicture = null
        ),
        status = UserStatus.ACTIVE,
        customAttributes = emptyMap()
    )
}
