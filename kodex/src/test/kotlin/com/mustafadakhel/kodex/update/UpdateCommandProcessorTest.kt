package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.extension.UserProfileUpdateData
import com.mustafadakhel.kodex.extension.UserUpdateData
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import com.mustafadakhel.kodex.util.now
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.TimeZone
import java.util.*

class UpdateCommandProcessorTest : DescribeSpec({

    describe("UpdateCommandProcessor") {
        val mockRepository = mockk<UserRepository>(relaxed = true)
        val mockHookExecutor = mockk<HookExecutor>(relaxed = true) {
            // Mock hooks to return pass-through values
            coEvery { executeBeforeUserUpdate(any(), any(), any()) } answers {
                UserUpdateData(
                    email = secondArg(),
                    phone = thirdArg()
                )
            }
            coEvery { executeBeforeProfileUpdate(any(), any(), any(), any(), any()) } answers {
                UserProfileUpdateData(
                    firstName = secondArg(),
                    lastName = thirdArg(),
                    address = arg(3),
                    profilePicture = arg(4)
                )
            }
            coEvery { executeBeforeCustomAttributesUpdate(any(), any()) } answers { secondArg() }
        }
        val changeTracker = ChangeTracker()
        val timeZone = TimeZone.UTC

        val processor = UpdateCommandProcessor(
            userRepository = mockRepository,
            hookExecutor = mockHookExecutor,
            changeTracker = changeTracker,
            timeZone = timeZone
        )

        val testUserId = UUID.randomUUID()
        val testUser = FullUserEntity(
            id = testUserId,
            email = "old@example.com",
            phoneNumber = "+1234567890",
            status = UserStatus.ACTIVE,
            createdAt = now(timeZone),
            updatedAt = now(timeZone),
            lastLoggedIn = null,
            roles = emptyList(),
            profile = null,
            customAttributes = emptyMap()
        )

        beforeEach {
            every { mockRepository.findFullById(testUserId) } returns testUser
        }

        describe("execute with UpdateUserFields") {
            it("should update email successfully") {
                val updatedUser = testUser.copy(email = "new@example.com")

                every { mockRepository.updateById(any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.Success
                every { mockRepository.findFullById(testUserId) } returns testUser andThen updatedUser

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com"))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.updateById(
                    userId = testUserId,
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.NoChange(),
                    status = FieldUpdate.NoChange(),
                    currentTime = any()
                )}
            }

            it("should handle email already exists") {
                every { mockRepository.updateById(any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.EmailAlreadyExists

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(email = FieldUpdate.SetValue("exists@example.com"))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Failure.ConstraintViolation>()
                result as UpdateResult.Failure.ConstraintViolation
                result.field shouldBe "email"
            }

            it("should handle phone already exists") {
                every { mockRepository.updateById(any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.PhoneAlreadyExists

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(phone = FieldUpdate.SetValue("+9876543210"))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Failure.ConstraintViolation>()
                result as UpdateResult.Failure.ConstraintViolation
                result.field shouldBe "phone"
            }

            it("should update multiple fields at once") {
                every { mockRepository.updateById(any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.Success

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(
                        email = FieldUpdate.SetValue("new@example.com"),
                        phone = FieldUpdate.SetValue("+9999999999"),
                        status = FieldUpdate.SetValue(UserStatus.SUSPENDED)
                    )
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.updateById(
                    userId = testUserId,
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.SetValue("+9999999999"),
                    status = FieldUpdate.SetValue(UserStatus.SUSPENDED),
                    currentTime = any()
                )}
            }

            it("should handle null email (clear email)") {
                every { mockRepository.updateById(any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.Success

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(email = FieldUpdate.ClearValue())
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.updateById(
                    userId = testUserId,
                    email = FieldUpdate.ClearValue(),
                    phone = FieldUpdate.NoChange(),
                    status = FieldUpdate.NoChange(),
                    currentTime = any()
                )}
            }

            it("should update status successfully") {
                every { mockRepository.updateById(any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.Success

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(status = FieldUpdate.SetValue(UserStatus.SUSPENDED))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.updateById(
                    userId = testUserId,
                    email = FieldUpdate.NoChange(),
                    phone = FieldUpdate.NoChange(),
                    status = FieldUpdate.SetValue(UserStatus.SUSPENDED),
                    currentTime = any()
                )}
            }
        }

        describe("execute with UpdateProfileFields") {
            val testUserWithProfile = testUser.copy(
                profile = UserProfileEntity(
                    userId = testUserId,
                    firstName = "Old",
                    lastName = "Name",
                    address = null,
                    profilePicture = null
                )
            )

            beforeEach {
                every { mockRepository.findFullById(testUserId) } returns testUserWithProfile
            }

            it("should update profile fields successfully") {
                every { mockRepository.findFullById(testUserId) } returns testUserWithProfile andThen testUserWithProfile
                every { mockRepository.updateProfileByUserId(any(), any()) } returns
                    UserRepository.UpdateProfileResult.Success(mockk(relaxed = true))

                val command = UpdateProfileFields(
                    userId = testUserId,
                    fields = ProfileFieldUpdates(
                        firstName = FieldUpdate.SetValue("New"),
                        lastName = FieldUpdate.SetValue("Name")
                    )
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
            }

            it("should clear profile fields with null") {
                every { mockRepository.findFullById(testUserId) } returns testUserWithProfile andThen testUserWithProfile
                every { mockRepository.updateProfileByUserId(any(), any()) } returns
                    UserRepository.UpdateProfileResult.Success(mockk(relaxed = true))

                val command = UpdateProfileFields(
                    userId = testUserId,
                    fields = ProfileFieldUpdates(
                        address = FieldUpdate.ClearValue(),
                        profilePicture = FieldUpdate.ClearValue()
                    )
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
            }

            it("should handle user without existing profile") {
                every { mockRepository.findFullById(testUserId) } returns testUser andThen testUser
                every { mockRepository.updateProfileByUserId(any(), any()) } returns
                    UserRepository.UpdateProfileResult.Success(mockk(relaxed = true))

                val command = UpdateProfileFields(
                    userId = testUserId,
                    fields = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("New"))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
            }
        }

        describe("execute with UpdateAttributes") {
            val testUserWithAttrs = testUser.copy(
                customAttributes = mapOf("key1" to "value1", "key2" to "value2")
            )

            beforeEach {
                every { mockRepository.findFullById(testUserId) } returns testUserWithAttrs
            }

            it("should add new attributes") {
                every { mockRepository.updateCustomAttributesByUserId(any(), any()) } returns
                    UserRepository.UpdateUserResult.Success

                val command = UpdateAttributes(
                    userId = testUserId,
                    changes = AttributeChanges(listOf(
                        AttributeChange.Set("key3", "value3")
                    ))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
            }

            it("should replace all attributes") {
                val updatedUser = testUserWithAttrs.copy(customAttributes = mapOf("newKey" to "newValue"))

                every { mockRepository.replaceAllCustomAttributesByUserId(any(), any()) } returns
                    UserRepository.UpdateUserResult.Success
                every { mockRepository.findFullById(testUserId) } returns testUserWithAttrs andThen updatedUser

                val newAttrs = mapOf("newKey" to "newValue")
                val command = UpdateAttributes(
                    userId = testUserId,
                    changes = AttributeChanges(listOf(
                        AttributeChange.ReplaceAll(newAttrs)
                    ))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.replaceAllCustomAttributesByUserId(testUserId, newAttrs) }
            }

            it("should remove specific attributes") {
                val updatedUser = testUserWithAttrs.copy(customAttributes = mapOf("key2" to "value2"))

                every { mockRepository.updateCustomAttributesByUserId(any(), any()) } returns
                    UserRepository.UpdateUserResult.Success
                every { mockRepository.findFullById(testUserId) } returns testUserWithAttrs andThen updatedUser

                val command = UpdateAttributes(
                    userId = testUserId,
                    changes = AttributeChanges(listOf(
                        AttributeChange.Remove("key1")
                    ))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
            }

            it("should clear all attributes with empty map") {
                val updatedUser = testUserWithAttrs.copy(customAttributes = emptyMap())

                every { mockRepository.replaceAllCustomAttributesByUserId(any(), any()) } returns
                    UserRepository.UpdateUserResult.Success
                every { mockRepository.findFullById(testUserId) } returns testUserWithAttrs andThen updatedUser

                val command = UpdateAttributes(
                    userId = testUserId,
                    changes = AttributeChanges(listOf(
                        AttributeChange.ReplaceAll(emptyMap())
                    ))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.replaceAllCustomAttributesByUserId(testUserId, emptyMap()) }
            }

            it("should handle user with no existing attributes") {
                every { mockRepository.updateCustomAttributesByUserId(any(), any()) } returns
                    UserRepository.UpdateUserResult.Success

                val command = UpdateAttributes(
                    userId = testUserId,
                    changes = AttributeChanges(listOf(
                        AttributeChange.Set("newKey", "newValue")
                    ))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
            }
        }

        describe("execute with UpdateUserBatch") {
            it("should update all fields atomically") {
                val updatedUser = testUser.copy(
                    email = "new@example.com",
                    customAttributes = mapOf("key" to "value")
                )

                every { mockRepository.updateBatch(any(), any(), any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.Success
                every { mockRepository.findFullById(testUserId) } returns testUser andThen updatedUser

                val command = UpdateUserBatch(
                    userId = testUserId,
                    userFields = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com")),
                    profileFields = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John")),
                    attributeChanges = AttributeChanges(listOf(AttributeChange.Set("key", "value")))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                verify { mockRepository.updateBatch(
                    userId = testUserId,
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.NoChange(),
                    status = FieldUpdate.NoChange(),
                    profile = any(),
                    customAttributes = FieldUpdate.SetValue(mapOf("key" to "value")),
                    currentTime = any()
                )}
            }

            it("should handle batch failures") {
                every { mockRepository.updateBatch(any(), any(), any(), any(), any(), any(), any()) } returns
                    UserRepository.UpdateUserResult.EmailAlreadyExists

                val command = UpdateUserBatch(
                    userId = testUserId,
                    userFields = UserFieldUpdates(email = FieldUpdate.SetValue("exists@example.com"))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Failure.ConstraintViolation>()
            }
        }

        describe("user not found") {
            it("should return NotFound when user doesn't exist") {
                every { mockRepository.findFullById(testUserId) } returns null

                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates(email = FieldUpdate.SetValue("test@example.com"))
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Failure.NotFound>()
                result as UpdateResult.Failure.NotFound
                result.userId shouldBe testUserId
            }
        }

        describe("no changes") {
            it("should return success with empty changes when no fields updated") {
                val command = UpdateUserFields(
                    userId = testUserId,
                    fields = UserFieldUpdates() // All NoChange
                )

                val result = processor.execute(command)

                result.shouldBeInstanceOf<UpdateResult.Success>()
                result as UpdateResult.Success
                result.changes.changedFields.isEmpty() shouldBe true
            }
        }
    }
})
