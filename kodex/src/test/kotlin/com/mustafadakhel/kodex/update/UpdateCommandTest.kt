package com.mustafadakhel.kodex.update

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class UpdateCommandTest : DescribeSpec({

    describe("UpdateUserFields") {
        it("should create command with userId and fields") {
            val userId = UUID.randomUUID()
            val fields = UserFieldUpdates(
                email = FieldUpdate.SetValue("new@example.com")
            )

            val command = UpdateUserFields(userId, fields)

            command.userId shouldBe userId
            command.fields shouldBe fields
        }

        it("should be an UpdateCommand") {
            val command = UpdateUserFields(
                userId = UUID.randomUUID(),
                fields = UserFieldUpdates()
            )

            command.shouldBeInstanceOf<UpdateCommand>()
        }

        it("should support data class copy") {
            val original = UpdateUserFields(
                userId = UUID.randomUUID(),
                fields = UserFieldUpdates()
            )
            val newUserId = UUID.randomUUID()

            val copy = original.copy(userId = newUserId)

            copy.userId shouldBe newUserId
            copy.fields shouldBe original.fields
        }
    }

    describe("UpdateProfileFields") {
        it("should create command with userId and profile fields") {
            val userId = UUID.randomUUID()
            val fields = ProfileFieldUpdates(
                firstName = FieldUpdate.SetValue("John"),
                lastName = FieldUpdate.SetValue("Doe")
            )

            val command = UpdateProfileFields(userId, fields)

            command.userId shouldBe userId
            command.fields shouldBe fields
        }

        it("should be an UpdateCommand") {
            val command = UpdateProfileFields(
                userId = UUID.randomUUID(),
                fields = ProfileFieldUpdates()
            )

            command.shouldBeInstanceOf<UpdateCommand>()
        }

        it("should support empty profile updates") {
            val command = UpdateProfileFields(
                userId = UUID.randomUUID(),
                fields = ProfileFieldUpdates()
            )

            command.fields.shouldNotBeNull()
        }
    }

    describe("UpdateAttributes") {
        it("should create command with userId and attribute changes") {
            val userId = UUID.randomUUID()
            val changes = AttributeChanges(
                changes = listOf(
                    AttributeChange.Set("department", "Engineering"),
                    AttributeChange.Remove("temp_field")
                )
            )

            val command = UpdateAttributes(userId, changes)

            command.userId shouldBe userId
            command.changes shouldBe changes
        }

        it("should be an UpdateCommand") {
            val command = UpdateAttributes(
                userId = UUID.randomUUID(),
                changes = AttributeChanges(changes = emptyList())
            )

            command.shouldBeInstanceOf<UpdateCommand>()
        }

        it("should support empty attribute changes") {
            val command = UpdateAttributes(
                userId = UUID.randomUUID(),
                changes = AttributeChanges(changes = emptyList())
            )

            command.changes.shouldNotBeNull()
        }
    }

    describe("UpdateUserBatch") {
        it("should create batch command with all fields") {
            val userId = UUID.randomUUID()
            val userFields = UserFieldUpdates(email = FieldUpdate.SetValue("test@example.com"))
            val profileFields = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John"))
            val attributeChanges = AttributeChanges(changes = listOf(AttributeChange.Set("key", "value")))

            val batch = UpdateUserBatch(
                userId = userId,
                userFields = userFields,
                profileFields = profileFields,
                attributeChanges = attributeChanges
            )

            batch.userId shouldBe userId
            batch.userFields shouldBe userFields
            batch.profileFields shouldBe profileFields
            batch.attributeChanges shouldBe attributeChanges
        }

        it("should create batch with only user fields") {
            val batch = UpdateUserBatch(
                userId = UUID.randomUUID(),
                userFields = UserFieldUpdates(email = FieldUpdate.SetValue("test@example.com")),
                profileFields = null,
                attributeChanges = null
            )

            batch.userFields.shouldNotBeNull()
            batch.profileFields.shouldBeNull()
            batch.attributeChanges.shouldBeNull()
        }

        it("should create batch with only profile fields") {
            val batch = UpdateUserBatch(
                userId = UUID.randomUUID(),
                userFields = null,
                profileFields = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John")),
                attributeChanges = null
            )

            batch.userFields.shouldBeNull()
            batch.profileFields.shouldNotBeNull()
            batch.attributeChanges.shouldBeNull()
        }

        it("should create batch with only attribute changes") {
            val batch = UpdateUserBatch(
                userId = UUID.randomUUID(),
                userFields = null,
                profileFields = null,
                attributeChanges = AttributeChanges(changes = listOf(AttributeChange.Set("key", "value")))
            )

            batch.userFields.shouldBeNull()
            batch.profileFields.shouldBeNull()
            batch.attributeChanges.shouldNotBeNull()
        }

        it("should support default null values") {
            val batch = UpdateUserBatch(userId = UUID.randomUUID())

            batch.userFields.shouldBeNull()
            batch.profileFields.shouldBeNull()
            batch.attributeChanges.shouldBeNull()
        }

        it("should be an UpdateCommand") {
            val batch = UpdateUserBatch(userId = UUID.randomUUID())
            batch.shouldBeInstanceOf<UpdateCommand>()
        }

        describe("hasChanges") {
            it("should return true when user fields have changes") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    userFields = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com"))
                )

                batch.hasChanges() shouldBe true
            }

            it("should return true when profile fields have changes") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    profileFields = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John"))
                )

                batch.hasChanges() shouldBe true
            }

            it("should return true when attribute changes exist") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    attributeChanges = AttributeChanges(changes = listOf(AttributeChange.Set("key", "value")))
                )

                batch.hasChanges() shouldBe true
            }

            it("should return false when all fields are null") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    userFields = null,
                    profileFields = null,
                    attributeChanges = null
                )

                batch.hasChanges() shouldBe false
            }

            it("should return false when all fields have no changes") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    userFields = UserFieldUpdates(),  // No changes
                    profileFields = ProfileFieldUpdates(),  // No changes
                    attributeChanges = AttributeChanges(changes = emptyList())  // No changes
                )

                batch.hasChanges() shouldBe false
            }

            it("should return true when multiple sections have changes") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    userFields = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com")),
                    profileFields = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John")),
                    attributeChanges = AttributeChanges(changes = listOf(AttributeChange.Set("key", "value")))
                )

                batch.hasChanges() shouldBe true
            }

            it("should return true when any section has changes") {
                val batch = UpdateUserBatch(
                    userId = UUID.randomUUID(),
                    userFields = null,
                    profileFields = null,
                    attributeChanges = AttributeChanges(changes = listOf(AttributeChange.Remove("old_key")))
                )

                batch.hasChanges() shouldBe true
            }
        }
    }

    describe("UpdateCommand interface") {
        it("should ensure all commands have userId") {
            val userId = UUID.randomUUID()

            val commands: List<UpdateCommand> = listOf(
                UpdateUserFields(userId, UserFieldUpdates()),
                UpdateProfileFields(userId, ProfileFieldUpdates()),
                UpdateAttributes(userId, AttributeChanges(changes = emptyList())),
                UpdateUserBatch(userId)
            )

            commands.forEach { command ->
                command.userId shouldBe userId
            }
        }
    }
})
