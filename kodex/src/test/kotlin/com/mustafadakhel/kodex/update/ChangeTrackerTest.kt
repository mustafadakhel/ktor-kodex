package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.util.now
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import java.util.*

class ChangeTrackerTest : DescribeSpec({

    describe("ChangeTracker") {
        val tracker = ChangeTracker()
        val testUserId = UUID.randomUUID()
        val currentTime = now(TimeZone.UTC)

        val baseUser = FullUser(
            id = testUserId,
            email = "old@example.com",
            phoneNumber = "+1234567890",
            isVerified = false,
            status = UserStatus.ACTIVE,
            createdAt = currentTime,
            updatedAt = currentTime,
            lastLoggedIn = null,
            roles = emptyList(),
            profile = null,
            customAttributes = emptyMap()
        )

        describe("detectUserFieldChanges") {
            it("should detect email change") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("new@example.com")
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 1
                changes["email"]?.oldValue shouldBe "old@example.com"
                changes["email"]?.newValue shouldBe "new@example.com"
            }

            it("should detect phone change") {
                val updates = UserFieldUpdates(
                    phone = FieldUpdate.SetValue("+9876543210")
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 1
                changes["phoneNumber"]?.oldValue shouldBe "+1234567890"
                changes["phoneNumber"]?.newValue shouldBe "+9876543210"
            }

            it("should detect status change") {
                val updates = UserFieldUpdates(
                    status = FieldUpdate.SetValue(UserStatus.SUSPENDED)
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 1
                changes["status"]?.oldValue shouldBe UserStatus.ACTIVE
                changes["status"]?.newValue shouldBe UserStatus.SUSPENDED
            }

            it("should detect verified change") {
                val updates = UserFieldUpdates(
                    isVerified = FieldUpdate.SetValue(true)
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 1
                changes["isVerified"]?.oldValue shouldBe false
                changes["isVerified"]?.newValue shouldBe true
            }

            it("should detect multiple changes") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.SetValue("+9876543210"),
                    isVerified = FieldUpdate.SetValue(true)
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 3
                changes.keys shouldContainExactly setOf("email", "phoneNumber", "isVerified")
            }

            it("should not detect changes when value is same") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("old@example.com") // Same as current
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 0
            }

            it("should not detect changes for NoChange") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.NoChange()
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 0
            }

            it("should detect clearing email to null") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.ClearValue()
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 1
                changes["email"]?.oldValue shouldBe "old@example.com"
                changes["email"]?.newValue shouldBe null
            }

            it("should detect clearing phone to null") {
                val updates = UserFieldUpdates(
                    phone = FieldUpdate.ClearValue()
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 1
                changes["phoneNumber"]?.oldValue shouldBe "+1234567890"
                changes["phoneNumber"]?.newValue shouldBe null
            }

            it("should detect setting email from null to value") {
                val userWithNullEmail = baseUser.copy(email = null)
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("new@example.com")
                )

                val changes = tracker.detectUserFieldChanges(userWithNullEmail, updates)

                changes.size shouldBe 1
                changes["email"]?.oldValue shouldBe null
                changes["email"]?.newValue shouldBe "new@example.com"
            }

            it("should handle mix of SetValue, ClearValue, and NoChange") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.ClearValue(),
                    isVerified = FieldUpdate.NoChange(),
                    status = FieldUpdate.SetValue(UserStatus.SUSPENDED)
                )

                val changes = tracker.detectUserFieldChanges(baseUser, updates)

                changes.size shouldBe 3
                changes.keys shouldContainExactly setOf("email", "phoneNumber", "status")
                changes["email"]?.newValue shouldBe "new@example.com"
                changes["phoneNumber"]?.newValue shouldBe null
                changes["status"]?.newValue shouldBe UserStatus.SUSPENDED
            }
        }

        describe("detectProfileFieldChanges") {
            val currentProfile = UserProfile(
                firstName = "John",
                lastName = "Doe",
                address = "123 Street",
                profilePicture = "photo.jpg"
            )

            it("should detect firstName change") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("Jane")
                )

                val changes = tracker.detectProfileFieldChanges(currentProfile, updates)

                changes.size shouldBe 1
                changes["profile.firstName"]?.oldValue shouldBe "John"
                changes["profile.firstName"]?.newValue shouldBe "Jane"
            }

            it("should detect multiple profile changes") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("Jane"),
                    lastName = FieldUpdate.SetValue("Smith")
                )

                val changes = tracker.detectProfileFieldChanges(currentProfile, updates)

                changes.size shouldBe 2
                changes["profile.firstName"]?.newValue shouldBe "Jane"
                changes["profile.lastName"]?.newValue shouldBe "Smith"
            }

            it("should detect clearing profile field") {
                val updates = ProfileFieldUpdates(
                    address = FieldUpdate.ClearValue()
                )

                val changes = tracker.detectProfileFieldChanges(currentProfile, updates)

                changes.size shouldBe 1
                changes["profile.address"]?.oldValue shouldBe "123 Street"
                changes["profile.address"]?.newValue shouldBe null
            }

            it("should handle null current profile") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("John")
                )

                val changes = tracker.detectProfileFieldChanges(null, updates)

                changes.size shouldBe 1
                changes["profile.firstName"]?.oldValue shouldBe null
                changes["profile.firstName"]?.newValue shouldBe "John"
            }

            it("should handle NoChange fields") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.NoChange(),
                    lastName = FieldUpdate.SetValue("Smith")
                )

                val changes = tracker.detectProfileFieldChanges(currentProfile, updates)

                changes.size shouldBe 1
                changes["profile.lastName"]?.newValue shouldBe "Smith"
            }

            it("should not detect changes when value is same") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("John")
                )

                val changes = tracker.detectProfileFieldChanges(currentProfile, updates)

                changes.size shouldBe 0
            }

            it("should handle setting all fields when profile is null") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("Jane"),
                    lastName = FieldUpdate.SetValue("Doe"),
                    address = FieldUpdate.SetValue("456 Avenue"),
                    profilePicture = FieldUpdate.SetValue("avatar.png")
                )

                val changes = tracker.detectProfileFieldChanges(null, updates)

                changes.size shouldBe 4
                changes["profile.firstName"]?.newValue shouldBe "Jane"
                changes["profile.lastName"]?.newValue shouldBe "Doe"
                changes["profile.address"]?.newValue shouldBe "456 Avenue"
                changes["profile.profilePicture"]?.newValue shouldBe "avatar.png"
            }
        }

        describe("detectAttributeChanges") {
            val currentAttrs = mapOf(
                "key1" to "value1",
                "key2" to "value2",
                "key3" to "value3"
            )

            it("should detect new attribute") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key4", "value4")
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 1
                detected["customAttributes.key4"]?.oldValue shouldBe null
                detected["customAttributes.key4"]?.newValue shouldBe "value4"
            }

            it("should detect updated attribute") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key1", "newValue1")
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 1
                detected["customAttributes.key1"]?.oldValue shouldBe "value1"
                detected["customAttributes.key1"]?.newValue shouldBe "newValue1"
            }

            it("should detect removed attribute") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Remove("key1")
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 1
                detected["customAttributes.key1"]?.oldValue shouldBe "value1"
                detected["customAttributes.key1"]?.newValue shouldBe null
            }

            it("should detect replace all changes") {
                val newAttrs = mapOf("newKey" to "newValue")
                val changes = AttributeChanges(listOf(
                    AttributeChange.ReplaceAll(newAttrs)
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                // Should detect removal of old keys and addition of new key
                detected.size shouldBe 4 // 3 removed + 1 added
                detected["customAttributes.key1"]?.newValue shouldBe null
                detected["customAttributes.key2"]?.newValue shouldBe null
                detected["customAttributes.key3"]?.newValue shouldBe null
                detected["customAttributes.newKey"]?.newValue shouldBe "newValue"
            }

            it("should handle empty current attributes") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key1", "value1")
                ))

                val detected = tracker.detectAttributeChanges(emptyMap(), changes)

                detected.size shouldBe 1
                detected["customAttributes.key1"]?.oldValue shouldBe null
                detected["customAttributes.key1"]?.newValue shouldBe "value1"
            }

            it("should handle ReplaceAll with empty map (clear all)") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.ReplaceAll(emptyMap())
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 3
                detected["customAttributes.key1"]?.newValue shouldBe null
                detected["customAttributes.key2"]?.newValue shouldBe null
                detected["customAttributes.key3"]?.newValue shouldBe null
            }

            it("should handle removing non-existent key") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Remove("nonExistentKey")
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 0
            }

            it("should handle multiple operations in sequence") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key1", "newValue1"),
                    AttributeChange.Set("key4", "value4"),
                    AttributeChange.Remove("key2")
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 3
                detected["customAttributes.key1"]?.newValue shouldBe "newValue1"
                detected["customAttributes.key4"]?.newValue shouldBe "value4"
                detected["customAttributes.key2"]?.newValue shouldBe null
            }

            it("should not detect change when setting same value") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key1", "value1")
                ))

                val detected = tracker.detectAttributeChanges(currentAttrs, changes)

                detected.size shouldBe 0
            }
        }

        describe("detectBatchChanges") {
            it("should detect changes across all categories") {
                val batch = UpdateUserBatch(
                    userId = testUserId,
                    userFields = UserFieldUpdates(
                        email = FieldUpdate.SetValue("new@example.com")
                    ),
                    profileFields = ProfileFieldUpdates(
                        firstName = FieldUpdate.SetValue("Jane")
                    ),
                    attributeChanges = AttributeChanges(listOf(
                        AttributeChange.Set("newKey", "newValue")
                    ))
                )

                val changeSet = tracker.detectBatchChanges(baseUser, batch)

                changeSet.changedFields.size shouldBe 3
                changeSet.changedFields["email"]?.newValue shouldBe "new@example.com"
                changeSet.changedFields["profile.firstName"]?.newValue shouldBe "Jane"
                changeSet.changedFields["customAttributes.newKey"]?.newValue shouldBe "newValue"
            }

            it("should handle batch with only user fields") {
                val batch = UpdateUserBatch(
                    userId = testUserId,
                    userFields = UserFieldUpdates(
                        email = FieldUpdate.SetValue("new@example.com"),
                        status = FieldUpdate.SetValue(UserStatus.SUSPENDED)
                    )
                )

                val changeSet = tracker.detectBatchChanges(baseUser, batch)

                changeSet.changedFields.size shouldBe 2
                changeSet.changedFields["email"]?.newValue shouldBe "new@example.com"
                changeSet.changedFields["status"]?.newValue shouldBe UserStatus.SUSPENDED
            }

            it("should handle batch with only profile fields") {
                val userWithProfile = baseUser.copy(
                    profile = UserProfile(
                        firstName = "John",
                        lastName = "Doe",
                        address = null,
                        profilePicture = null
                    )
                )

                val batch = UpdateUserBatch(
                    userId = testUserId,
                    profileFields = ProfileFieldUpdates(
                        firstName = FieldUpdate.SetValue("Jane"),
                        address = FieldUpdate.SetValue("123 Main St")
                    )
                )

                val changeSet = tracker.detectBatchChanges(userWithProfile, batch)

                changeSet.changedFields.size shouldBe 2
                changeSet.changedFields["profile.firstName"]?.newValue shouldBe "Jane"
                changeSet.changedFields["profile.address"]?.newValue shouldBe "123 Main St"
            }

            it("should handle batch with only attribute changes") {
                val userWithAttrs = baseUser.copy(
                    customAttributes = mapOf("key1" to "value1")
                )

                val batch = UpdateUserBatch(
                    userId = testUserId,
                    attributeChanges = AttributeChanges(listOf(
                        AttributeChange.Set("key2", "value2"),
                        AttributeChange.Remove("key1")
                    ))
                )

                val changeSet = tracker.detectBatchChanges(userWithAttrs, batch)

                changeSet.changedFields.size shouldBe 2
                changeSet.changedFields["customAttributes.key2"]?.newValue shouldBe "value2"
                changeSet.changedFields["customAttributes.key1"]?.newValue shouldBe null
            }

            it("should handle empty batch (no changes)") {
                val batch = UpdateUserBatch(userId = testUserId)

                val changeSet = tracker.detectBatchChanges(baseUser, batch)

                changeSet.changedFields.size shouldBe 0
            }

            it("should handle batch with all null sections") {
                val batch = UpdateUserBatch(
                    userId = testUserId,
                    userFields = null,
                    profileFields = null,
                    attributeChanges = null
                )

                val changeSet = tracker.detectBatchChanges(baseUser, batch)

                changeSet.changedFields.size shouldBe 0
            }
        }
    }
})
