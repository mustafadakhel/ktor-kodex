package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.util.getCurrentLocalDateTime
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import java.util.*

class ChangeTrackerTest : DescribeSpec({

    describe("ChangeTracker") {
        val tracker = ChangeTracker()
        val testUserId = UUID.randomUUID()
        val currentTime = getCurrentLocalDateTime(TimeZone.UTC)

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
        }
    }
})
