package com.mustafadakhel.kodex.extension

import com.mustafadakhel.kodex.model.UserProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.UUID

class HookExecutorTest : FunSpec({

    context("Single Hook Execution") {
        test("should execute single beforeUserCreate hook") {
            val hook = object : UserLifecycleHooks {
                override suspend fun beforeUserCreate(
                    email: String?,
                    phone: String?,
                    password: String,
                    customAttributes: Map<String, String>?,
                    profile: UserProfile?
                ): UserCreateData {
                    return UserCreateData(
                        email = email?.uppercase(),
                        phone = phone,
                        customAttributes = customAttributes,
                        profile = profile
                    )
                }
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to hook))
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeUserCreate(
                    email = "test@example.com",
                    phone = null,
                    password = "pass123",
                    customAttributes = null,
                    profile = null
                )
            }

            result.email shouldBe "TEST@EXAMPLE.COM"
        }

        test("should execute single beforeUserUpdate hook") {
            val userId = UUID.randomUUID()
            val hook = object : UserLifecycleHooks {
                override suspend fun beforeUserUpdate(
                    userId: UUID,
                    email: String?,
                    phone: String?
                ): UserUpdateData {
                    return UserUpdateData(
                        email = email?.trim(),
                        phone = phone?.replace("-", "")
                    )
                }
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to hook))
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeUserUpdate(
                    userId = userId,
                    email = "  test@example.com  ",
                    phone = "+1-234-567-8900"
                )
            }

            result.email shouldBe "test@example.com"
            result.phone shouldBe "+12345678900"
        }

        test("should execute single beforeProfileUpdate hook") {
            val userId = UUID.randomUUID()
            val hook = object : UserLifecycleHooks {
                override suspend fun beforeProfileUpdate(
                    userId: UUID,
                    firstName: String?,
                    lastName: String?,
                    address: String?,
                    profilePicture: String?
                ): UserProfileUpdateData {
                    return UserProfileUpdateData(
                        firstName = firstName?.trim(),
                        lastName = lastName?.trim(),
                        address = address?.trim(),
                        profilePicture = profilePicture
                    )
                }
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to hook))
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeProfileUpdate(
                    userId = userId,
                    firstName = "  John  ",
                    lastName = "  Doe  ",
                    address = "  123 Main St  ",
                    profilePicture = null
                )
            }

            result.firstName shouldBe "John"
            result.lastName shouldBe "Doe"
            result.address shouldBe "123 Main St"
        }

        test("should execute single beforeCustomAttributesUpdate hook") {
            val userId = UUID.randomUUID()
            val hook = object : UserLifecycleHooks {
                override suspend fun beforeCustomAttributesUpdate(
                    userId: UUID,
                    customAttributes: Map<String, String>
                ): Map<String, String> {
                    return customAttributes.mapValues { it.value.uppercase() }
                }
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to hook))
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeCustomAttributesUpdate(
                    userId = userId,
                    customAttributes = mapOf("key1" to "value1", "key2" to "value2")
                )
            }

            result shouldContainExactly mapOf("key1" to "VALUE1", "key2" to "VALUE2")
        }

        test("should execute single beforeLogin hook") {
            val hook = object : UserLifecycleHooks {
                override suspend fun beforeLogin(identifier: String): String {
                    return identifier.lowercase().trim()
                }
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to hook))
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeLogin("  TEST@EXAMPLE.COM  ")
            }

            result shouldBe "test@example.com"
        }

        test("should execute single afterLoginFailure hook") {
            val capturedIdentifiers = mutableListOf<String>()

            val hook = object : UserLifecycleHooks {
                override suspend fun afterLoginFailure(identifier: String) {
                    capturedIdentifiers.add(identifier)
                }
            }

            val registry = ExtensionRegistry.from(mapOf(UserLifecycleHooks::class to hook))
            val executor = HookExecutor(registry)

            runBlocking {
                executor.executeAfterLoginFailure("test@example.com")
                executor.executeAfterLoginFailure("another@example.com")
            }

            capturedIdentifiers shouldContainExactly listOf("test@example.com", "another@example.com")
        }
    }

    context("Multiple Hook Chaining") {
        test("should chain multiple beforeUserCreate hooks") {
            val hook1 = object : UserLifecycleHooks {
                override suspend fun beforeUserCreate(
                    email: String?,
                    phone: String?,
                    password: String,
                    customAttributes: Map<String, String>?,
                    profile: UserProfile?
                ): UserCreateData {
                    return UserCreateData(
                        email = email?.trim(),
                        phone = phone,
                        customAttributes = customAttributes,
                        profile = profile
                    )
                }
            }

            val hook2 = object : UserLifecycleHooks {
                override suspend fun beforeUserCreate(
                    email: String?,
                    phone: String?,
                    password: String,
                    customAttributes: Map<String, String>?,
                    profile: UserProfile?
                ): UserCreateData {
                    return UserCreateData(
                        email = email?.lowercase(),
                        phone = phone,
                        customAttributes = customAttributes,
                        profile = profile
                    )
                }
            }

            val hook3 = object : UserLifecycleHooks {
                override suspend fun beforeUserCreate(
                    email: String?,
                    phone: String?,
                    password: String,
                    customAttributes: Map<String, String>?,
                    profile: UserProfile?
                ): UserCreateData {
                    return UserCreateData(
                        email = email?.plus("_transformed"),
                        phone = phone,
                        customAttributes = customAttributes,
                        profile = profile
                    )
                }
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(hook1, hook2, hook3))
            )
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeUserCreate(
                    email = "  TEST@EXAMPLE.COM  ",
                    phone = null,
                    password = "pass123",
                    customAttributes = null,
                    profile = null
                )
            }

            result.email shouldBe "test@example.com_transformed"
        }

        test("should chain multiple beforeCustomAttributesUpdate hooks") {
            val hook1 = object : UserLifecycleHooks {
                override suspend fun beforeCustomAttributesUpdate(
                    userId: UUID,
                    customAttributes: Map<String, String>
                ): Map<String, String> {
                    return customAttributes + ("added_by_hook1" to "value1")
                }
            }

            val hook2 = object : UserLifecycleHooks {
                override suspend fun beforeCustomAttributesUpdate(
                    userId: UUID,
                    customAttributes: Map<String, String>
                ): Map<String, String> {
                    return customAttributes + ("added_by_hook2" to "value2")
                }
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(hook1, hook2))
            )
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeCustomAttributesUpdate(
                    userId = UUID.randomUUID(),
                    customAttributes = mapOf("original" to "data")
                )
            }

            result shouldContainExactly mapOf(
                "original" to "data",
                "added_by_hook1" to "value1",
                "added_by_hook2" to "value2"
            )
        }

        test("should execute multiple afterLoginFailure hooks") {
            val hook1Calls = mutableListOf<String>()
            val hook2Calls = mutableListOf<String>()

            val hook1 = object : UserLifecycleHooks {
                override suspend fun afterLoginFailure(identifier: String) {
                    hook1Calls.add(identifier)
                }
            }

            val hook2 = object : UserLifecycleHooks {
                override suspend fun afterLoginFailure(identifier: String) {
                    hook2Calls.add(identifier)
                }
            }

            val registry = ExtensionRegistry.fromLists(
                mapOf(UserLifecycleHooks::class to listOf(hook1, hook2))
            )
            val executor = HookExecutor(registry)

            runBlocking {
                executor.executeAfterLoginFailure("test@example.com")
            }

            hook1Calls shouldContainExactly listOf("test@example.com")
            hook2Calls shouldContainExactly listOf("test@example.com")
        }
    }

    context("Empty Registry") {
        test("should handle empty registry for beforeUserCreate") {
            val registry = ExtensionRegistry.empty()
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeUserCreate(
                    email = "test@example.com",
                    phone = null,
                    password = "pass123",
                    customAttributes = null,
                    profile = null
                )
            }

            result.email shouldBe "test@example.com"
            result.phone shouldBe null
        }

        test("should handle empty registry for beforeUserUpdate") {
            val registry = ExtensionRegistry.empty()
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeUserUpdate(
                    userId = UUID.randomUUID(),
                    email = "test@example.com",
                    phone = "+1234567890"
                )
            }

            result.email shouldBe "test@example.com"
            result.phone shouldBe "+1234567890"
        }

        test("should handle empty registry for beforeProfileUpdate") {
            val registry = ExtensionRegistry.empty()
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeProfileUpdate(
                    userId = UUID.randomUUID(),
                    firstName = "John",
                    lastName = "Doe",
                    address = "123 Main St",
                    profilePicture = "pic.jpg"
                )
            }

            result.firstName shouldBe "John"
            result.lastName shouldBe "Doe"
            result.address shouldBe "123 Main St"
            result.profilePicture shouldBe "pic.jpg"
        }

        test("should handle empty registry for beforeCustomAttributesUpdate") {
            val registry = ExtensionRegistry.empty()
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeCustomAttributesUpdate(
                    userId = UUID.randomUUID(),
                    customAttributes = mapOf("key" to "value")
                )
            }

            result shouldContainExactly mapOf("key" to "value")
        }

        test("should handle empty registry for beforeLogin") {
            val registry = ExtensionRegistry.empty()
            val executor = HookExecutor(registry)

            val result = runBlocking {
                executor.executeBeforeLogin("test@example.com")
            }

            result shouldBe "test@example.com"
        }

        test("should handle empty registry for afterLoginFailure") {
            val registry = ExtensionRegistry.empty()
            val executor = HookExecutor(registry)

            runBlocking {
                executor.executeAfterLoginFailure("test@example.com")
            }
        }
    }
})
