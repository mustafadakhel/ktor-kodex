package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.UUID

class UserLifecycleHooksTest : DescribeSpec({

    class DefaultHooksImpl : UserLifecycleHooks

    val hooks = DefaultHooksImpl()
    val testUserId = UUID.randomUUID()
    val testLoginMetadata = LoginMetadata("192.168.1.1", "TestAgent")
    val testProfile = UserProfile(
        firstName = "John",
        lastName = "Doe",
        address = "123 Main St",
        profilePicture = "pic.jpg"
    )

    describe("UserLifecycleHooks default implementations") {
        describe("beforeUserCreate") {
            it("should return unchanged data with all fields") {
                val result = runBlocking {
                    hooks.beforeUserCreate(
                        email = "test@example.com",
                        phone = "+1234567890",
                        password = "password123",
                        customAttributes = mapOf("key" to "value"),
                        profile = testProfile
                    )
                }

                result.email shouldBe "test@example.com"
                result.phone shouldBe "+1234567890"
                result.customAttributes shouldBe mapOf("key" to "value")
                result.profile shouldBe testProfile
            }

            it("should return unchanged data with null fields") {
                val result = runBlocking {
                    hooks.beforeUserCreate(
                        email = null,
                        phone = null,
                        password = "password123",
                        customAttributes = null,
                        profile = null
                    )
                }

                result.email.shouldBeNull()
                result.phone.shouldBeNull()
                result.customAttributes.shouldBeNull()
                result.profile.shouldBeNull()
            }

            it("should return unchanged data with partial fields") {
                val result = runBlocking {
                    hooks.beforeUserCreate(
                        email = "user@test.com",
                        phone = null,
                        password = "pass",
                        customAttributes = mapOf("role" to "admin"),
                        profile = null
                    )
                }

                result.email shouldBe "user@test.com"
                result.phone.shouldBeNull()
                result.customAttributes shouldBe mapOf("role" to "admin")
                result.profile.shouldBeNull()
            }
        }

        describe("beforeUserUpdate") {
            it("should return unchanged data with both fields") {
                val result = runBlocking {
                    hooks.beforeUserUpdate(
                        userId = testUserId,
                        email = "updated@example.com",
                        phone = "+9876543210"
                    )
                }

                result.email shouldBe "updated@example.com"
                result.phone shouldBe "+9876543210"
            }

            it("should return unchanged data with null fields") {
                val result = runBlocking {
                    hooks.beforeUserUpdate(
                        userId = testUserId,
                        email = null,
                        phone = null
                    )
                }

                result.email.shouldBeNull()
                result.phone.shouldBeNull()
            }

            it("should return unchanged data with only email") {
                val result = runBlocking {
                    hooks.beforeUserUpdate(
                        userId = testUserId,
                        email = "email@test.com",
                        phone = null
                    )
                }

                result.email shouldBe "email@test.com"
                result.phone.shouldBeNull()
            }

            it("should return unchanged data with only phone") {
                val result = runBlocking {
                    hooks.beforeUserUpdate(
                        userId = testUserId,
                        email = null,
                        phone = "+1111111111"
                    )
                }

                result.email.shouldBeNull()
                result.phone shouldBe "+1111111111"
            }
        }

        describe("beforeProfileUpdate") {
            it("should return unchanged data with all fields") {
                val result = runBlocking {
                    hooks.beforeProfileUpdate(
                        userId = testUserId,
                        firstName = "Jane",
                        lastName = "Smith",
                        address = "456 Oak Ave",
                        profilePicture = "avatar.png"
                    )
                }

                result.firstName shouldBe "Jane"
                result.lastName shouldBe "Smith"
                result.address shouldBe "456 Oak Ave"
                result.profilePicture shouldBe "avatar.png"
            }

            it("should return unchanged data with null fields") {
                val result = runBlocking {
                    hooks.beforeProfileUpdate(
                        userId = testUserId,
                        firstName = null,
                        lastName = null,
                        address = null,
                        profilePicture = null
                    )
                }

                result.firstName.shouldBeNull()
                result.lastName.shouldBeNull()
                result.address.shouldBeNull()
                result.profilePicture.shouldBeNull()
            }

            it("should return unchanged data with partial fields") {
                val result = runBlocking {
                    hooks.beforeProfileUpdate(
                        userId = testUserId,
                        firstName = "Bob",
                        lastName = null,
                        address = "789 Pine St",
                        profilePicture = null
                    )
                }

                result.firstName shouldBe "Bob"
                result.lastName.shouldBeNull()
                result.address shouldBe "789 Pine St"
                result.profilePicture.shouldBeNull()
            }
        }

        describe("beforeCustomAttributesUpdate") {
            it("should return unchanged attributes") {
                val attributes = mapOf("key1" to "value1", "key2" to "value2")
                val result = runBlocking {
                    hooks.beforeCustomAttributesUpdate(testUserId, attributes)
                }

                result shouldBe attributes
            }

            it("should return unchanged empty attributes") {
                val result = runBlocking {
                    hooks.beforeCustomAttributesUpdate(testUserId, emptyMap())
                }

                result shouldBe emptyMap()
            }

            it("should return unchanged single attribute") {
                val result = runBlocking {
                    hooks.beforeCustomAttributesUpdate(testUserId, mapOf("role" to "admin"))
                }

                result shouldBe mapOf("role" to "admin")
            }
        }

        describe("beforeLogin") {
            it("should return unchanged email identifier") {
                val result = runBlocking {
                    hooks.beforeLogin("user@example.com", testLoginMetadata)
                }

                result shouldBe "user@example.com"
            }

            it("should return unchanged phone identifier") {
                val result = runBlocking {
                    hooks.beforeLogin("+1234567890", testLoginMetadata)
                }

                result shouldBe "+1234567890"
            }

            it("should return unchanged any identifier") {
                val result = runBlocking {
                    hooks.beforeLogin("custom-identifier-123", testLoginMetadata)
                }

                result shouldBe "custom-identifier-123"
            }
        }

        describe("afterLoginFailure") {
            it("should complete without error for email") {
                runBlocking {
                    hooks.afterLoginFailure("failed@example.com", testLoginMetadata)
                }
                // No assertion - just verify it doesn't throw
            }

            it("should complete without error for phone") {
                runBlocking {
                    hooks.afterLoginFailure("+9999999999", testLoginMetadata)
                }
                // No assertion - just verify it doesn't throw
            }

            it("should complete without error for any identifier") {
                runBlocking {
                    hooks.afterLoginFailure("any-failed-identifier", testLoginMetadata)
                }
                // No assertion - just verify it doesn't throw
            }
        }
    }

    describe("UserCreateData") {
        it("should store all fields") {
            val data = UserCreateData(
                email = "test@example.com",
                phone = "+1234567890",
                customAttributes = mapOf("key" to "value"),
                profile = testProfile
            )

            data.email shouldBe "test@example.com"
            data.phone shouldBe "+1234567890"
            data.customAttributes shouldBe mapOf("key" to "value")
            data.profile shouldBe testProfile
        }

        it("should support null fields") {
            val data = UserCreateData(null, null, null, null)

            data.email.shouldBeNull()
            data.phone.shouldBeNull()
            data.customAttributes.shouldBeNull()
            data.profile.shouldBeNull()
        }

        it("should support data class equality") {
            val data1 = UserCreateData("email@test.com", "+111", mapOf("a" to "b"), null)
            val data2 = UserCreateData("email@test.com", "+111", mapOf("a" to "b"), null)

            (data1 == data2) shouldBe true
        }

        it("should support data class copy") {
            val original = UserCreateData("old@test.com", "+111", null, null)
            val copy = original.copy(email = "new@test.com")

            copy.email shouldBe "new@test.com"
            copy.phone shouldBe "+111"
        }
    }

    describe("UserUpdateData") {
        it("should store both fields") {
            val data = UserUpdateData("updated@example.com", "+9876543210")

            data.email shouldBe "updated@example.com"
            data.phone shouldBe "+9876543210"
        }

        it("should support null fields") {
            val data = UserUpdateData(null, null)

            data.email.shouldBeNull()
            data.phone.shouldBeNull()
        }

        it("should support data class equality") {
            val data1 = UserUpdateData("test@example.com", "+123")
            val data2 = UserUpdateData("test@example.com", "+123")

            (data1 == data2) shouldBe true
        }

        it("should support data class copy") {
            val original = UserUpdateData("old@test.com", "+111")
            val copy = original.copy(phone = "+999")

            copy.email shouldBe "old@test.com"
            copy.phone shouldBe "+999"
        }
    }

    describe("UserProfileUpdateData") {
        it("should store all fields") {
            val data = UserProfileUpdateData("John", "Doe", "123 St", "pic.jpg")

            data.firstName shouldBe "John"
            data.lastName shouldBe "Doe"
            data.address shouldBe "123 St"
            data.profilePicture shouldBe "pic.jpg"
        }

        it("should support null fields") {
            val data = UserProfileUpdateData(null, null, null, null)

            data.firstName.shouldBeNull()
            data.lastName.shouldBeNull()
            data.address.shouldBeNull()
            data.profilePicture.shouldBeNull()
        }

        it("should support data class equality") {
            val data1 = UserProfileUpdateData("Jane", "Smith", null, null)
            val data2 = UserProfileUpdateData("Jane", "Smith", null, null)

            (data1 == data2) shouldBe true
        }

        it("should support data class copy") {
            val original = UserProfileUpdateData("John", "Doe", "old address", null)
            val copy = original.copy(address = "new address")

            copy.firstName shouldBe "John"
            copy.lastName shouldBe "Doe"
            copy.address shouldBe "new address"
        }
    }
})
