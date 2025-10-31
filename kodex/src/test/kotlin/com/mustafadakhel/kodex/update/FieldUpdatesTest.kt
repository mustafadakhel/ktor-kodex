package com.mustafadakhel.kodex.update

import com.mustafadakhel.kodex.model.UserStatus
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class FieldUpdatesTest : DescribeSpec({

    describe("FieldUpdate") {
        describe("NoChange") {
            it("should be equal to other NoChange instances") {
                val noChange1 = FieldUpdate.NoChange<String>()
                val noChange2 = FieldUpdate.NoChange<String>()
                noChange1 shouldBe noChange2
            }

            it("should have consistent hashCode") {
                val noChange = FieldUpdate.NoChange<String>()
                noChange.hashCode() shouldBe 0
            }

            it("should have correct toString") {
                val noChange = FieldUpdate.NoChange<String>()
                noChange.toString() shouldBe "NoChange"
            }
        }

        describe("SetValue") {
            it("should store the provided value") {
                val setValue = FieldUpdate.SetValue("test@example.com")
                setValue.value shouldBe "test@example.com"
            }

            it("should be equal when values are equal") {
                val setValue1 = FieldUpdate.SetValue("test@example.com")
                val setValue2 = FieldUpdate.SetValue("test@example.com")
                setValue1 shouldBe setValue2
            }

            it("should not be equal when values differ") {
                val setValue1 = FieldUpdate.SetValue("test1@example.com")
                val setValue2 = FieldUpdate.SetValue("test2@example.com")
                (setValue1 == setValue2) shouldBe false
            }
        }

        describe("ClearValue") {
            it("should be equal to other ClearValue instances") {
                val clearValue1 = FieldUpdate.ClearValue<String>()
                val clearValue2 = FieldUpdate.ClearValue<String>()
                clearValue1 shouldBe clearValue2
            }

            it("should have consistent hashCode") {
                val clearValue = FieldUpdate.ClearValue<String>()
                clearValue.hashCode() shouldBe 1
            }

            it("should have correct toString") {
                val clearValue = FieldUpdate.ClearValue<String>()
                clearValue.toString() shouldBe "ClearValue"
            }
        }
    }

    describe("Helper functions") {
        describe("asUpdate") {
            it("should convert value to SetValue") {
                val update = "test@example.com".asUpdate()
                update.shouldBeInstanceOf<FieldUpdate.SetValue<String>>()
                (update as FieldUpdate.SetValue).value shouldBe "test@example.com"
            }
        }

        describe("clearField") {
            it("should create ClearValue") {
                val update = clearField<String>()
                update.shouldBeInstanceOf<FieldUpdate.ClearValue<String>>()
            }
        }

        describe("noChange") {
            it("should create NoChange") {
                val update = noChange<String>()
                update.shouldBeInstanceOf<FieldUpdate.NoChange<String>>()
            }
        }

        describe("map") {
            it("should transform SetValue") {
                val update = FieldUpdate.SetValue(5)
                val mapped = update.map { it * 2 }
                mapped.shouldBeInstanceOf<FieldUpdate.SetValue<Int>>()
                (mapped as FieldUpdate.SetValue).value shouldBe 10
            }

            it("should preserve NoChange") {
                val update = FieldUpdate.NoChange<Int>()
                val mapped = update.map { it * 2 }
                mapped.shouldBeInstanceOf<FieldUpdate.NoChange<Int>>()
            }

            it("should preserve ClearValue") {
                val update = FieldUpdate.ClearValue<Int>()
                val mapped = update.map { it * 2 }
                mapped.shouldBeInstanceOf<FieldUpdate.ClearValue<Int>>()
            }
        }

        describe("valueOrNull") {
            it("should return value for SetValue") {
                val update = FieldUpdate.SetValue("test@example.com")
                update.valueOrNull() shouldBe "test@example.com"
            }

            it("should return null for NoChange") {
                val update = FieldUpdate.NoChange<String>()
                update.valueOrNull().shouldBeNull()
            }

            it("should return null for ClearValue") {
                val update = FieldUpdate.ClearValue<String>()
                update.valueOrNull().shouldBeNull()
            }
        }

        describe("hasChange") {
            it("should return true for SetValue") {
                val update = FieldUpdate.SetValue("test@example.com")
                update.hasChange() shouldBe true
            }

            it("should return false for NoChange") {
                val update = FieldUpdate.NoChange<String>()
                update.hasChange() shouldBe false
            }

            it("should return true for ClearValue") {
                val update = FieldUpdate.ClearValue<String>()
                update.hasChange() shouldBe true
            }
        }
    }

    describe("UserFieldUpdates") {
        describe("hasChanges") {
            it("should return false when all fields are NoChange") {
                val updates = UserFieldUpdates()
                updates.hasChanges() shouldBe false
            }

            it("should return true when email changes") {
                val updates = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com"))
                updates.hasChanges() shouldBe true
            }

            it("should return true when phone changes") {
                val updates = UserFieldUpdates(phone = FieldUpdate.SetValue("+1234567890"))
                updates.hasChanges() shouldBe true
            }

            it("should return true when status changes") {
                val updates = UserFieldUpdates(status = FieldUpdate.SetValue(UserStatus.SUSPENDED))
                updates.hasChanges() shouldBe true
            }

            it("should return true when multiple fields change") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.SetValue("+1234567890")
                )
                updates.hasChanges() shouldBe true
            }
        }

        describe("changedFields") {
            it("should return empty list when no changes") {
                val updates = UserFieldUpdates()
                updates.changedFields().shouldBeEmpty()
            }

            it("should return email when only email changes") {
                val updates = UserFieldUpdates(email = FieldUpdate.SetValue("new@example.com"))
                updates.changedFields() shouldContainExactly listOf("email")
            }

            it("should return all changed fields") {
                val updates = UserFieldUpdates(
                    email = FieldUpdate.SetValue("new@example.com"),
                    phone = FieldUpdate.SetValue("+1234567890")
                )
                updates.changedFields() shouldContainExactlyInAnyOrder listOf("email", "phone")
            }

            it("should include fields cleared with ClearValue") {
                val updates = UserFieldUpdates(email = FieldUpdate.ClearValue())
                updates.changedFields() shouldContain "email"
            }
        }
    }

    describe("ProfileFieldUpdates") {
        describe("hasChanges") {
            it("should return false when all fields are NoChange") {
                val updates = ProfileFieldUpdates()
                updates.hasChanges() shouldBe false
            }

            it("should return true when firstName changes") {
                val updates = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John"))
                updates.hasChanges() shouldBe true
            }

            it("should return true when lastName changes") {
                val updates = ProfileFieldUpdates(lastName = FieldUpdate.SetValue("Doe"))
                updates.hasChanges() shouldBe true
            }

            it("should return true when address changes") {
                val updates = ProfileFieldUpdates(address = FieldUpdate.SetValue("123 Main St"))
                updates.hasChanges() shouldBe true
            }

            it("should return true when profilePicture changes") {
                val updates = ProfileFieldUpdates(profilePicture = FieldUpdate.SetValue("avatar.jpg"))
                updates.hasChanges() shouldBe true
            }

            it("should return true when field is cleared") {
                val updates = ProfileFieldUpdates(address = FieldUpdate.ClearValue())
                updates.hasChanges() shouldBe true
            }
        }

        describe("changedFields") {
            it("should return empty list when no changes") {
                val updates = ProfileFieldUpdates()
                updates.changedFields().shouldBeEmpty()
            }

            it("should return firstName when only firstName changes") {
                val updates = ProfileFieldUpdates(firstName = FieldUpdate.SetValue("John"))
                updates.changedFields() shouldContainExactly listOf("firstName")
            }

            it("should return all changed fields") {
                val updates = ProfileFieldUpdates(
                    firstName = FieldUpdate.SetValue("John"),
                    lastName = FieldUpdate.SetValue("Doe"),
                    address = FieldUpdate.SetValue("123 Main St")
                )
                updates.changedFields() shouldContainExactlyInAnyOrder listOf("firstName", "lastName", "address")
            }
        }
    }

    describe("AttributeChanges") {
        describe("hasChanges") {
            it("should return false when changes list is empty") {
                val changes = AttributeChanges(emptyList())
                changes.hasChanges() shouldBe false
            }

            it("should return true when changes list is not empty") {
                val changes = AttributeChanges(listOf(AttributeChange.Set("key", "value")))
                changes.hasChanges() shouldBe true
            }
        }

        describe("affectedKeys") {
            it("should return empty set when no changes") {
                val changes = AttributeChanges(emptyList())
                changes.affectedKeys().shouldBeEmpty()
            }

            it("should return key for Set change") {
                val changes = AttributeChanges(listOf(AttributeChange.Set("key1", "value1")))
                changes.affectedKeys() shouldContainExactly setOf("key1")
            }

            it("should return key for Remove change") {
                val changes = AttributeChanges(listOf(AttributeChange.Remove("key1")))
                changes.affectedKeys() shouldContainExactly setOf("key1")
            }

            it("should return all keys for ReplaceAll change") {
                val newAttrs = mapOf("key1" to "value1", "key2" to "value2")
                val changes = AttributeChanges(listOf(AttributeChange.ReplaceAll(newAttrs)))
                changes.affectedKeys() shouldContainExactlyInAnyOrder setOf("key1", "key2")
            }

            it("should return all unique keys for multiple changes") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key1", "value1"),
                    AttributeChange.Set("key2", "value2"),
                    AttributeChange.Remove("key3")
                ))
                changes.affectedKeys() shouldContainExactlyInAnyOrder setOf("key1", "key2", "key3")
            }

            it("should deduplicate keys across changes") {
                val changes = AttributeChanges(listOf(
                    AttributeChange.Set("key1", "value1"),
                    AttributeChange.Set("key1", "value2")
                ))
                changes.affectedKeys() shouldContainExactly setOf("key1")
            }
        }
    }
})
