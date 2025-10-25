package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.FullUserEntity
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.model.database.UserProfileEntity
import com.mustafadakhel.kodex.model.database.toFullUser
import com.mustafadakhel.kodex.model.database.toUserProfile
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDateTime
import java.util.UUID

class UserQueryServiceTest : FunSpec({
    lateinit var userRepository: UserRepository
    lateinit var userQueryService: UserQueryService

    val testUserId = UUID.randomUUID()
    val testEmail = "test@example.com"
    val testPhone = "+1234567890"
    val testTime = LocalDateTime.parse("2024-01-01T12:00:00")

    val testUserEntity = UserEntity(
        id = testUserId,
        email = testEmail,
        phoneNumber = testPhone,
        createdAt = testTime,
        updatedAt = testTime,
        isVerified = true,
        lastLoggedIn = testTime,
        status = UserStatus.ACTIVE
    )

    val testProfileEntity = UserProfileEntity(
        userId = testUserId,
        firstName = "John",
        lastName = "Doe",
        address = "123 Main St",
        profilePicture = "avatar.jpg"
    )

    val testRoleEntity = RoleEntity(name = "user", description = "Regular user")

    val testFullUserEntity = FullUserEntity(
        id = testUserId,
        email = testEmail,
        phoneNumber = testPhone,
        createdAt = testTime,
        updatedAt = testTime,
        isVerified = true,
        lastLoggedIn = testTime,
        status = UserStatus.ACTIVE,
        roles = listOf(testRoleEntity),
        profile = testProfileEntity,
        customAttributes = mapOf("key1" to "value1")
    )

    beforeEach {
        userRepository = mockk()
        userQueryService = DefaultUserQueryService(userRepository)
    }

    test("getAllUsers should return list of users") {
        val entities = listOf(testUserEntity)
        every { userRepository.getAll() } returns entities

        val result = userQueryService.getAllUsers()

        result shouldHaveSize 1
        result[0].id shouldBe testUserId
        result[0].email shouldBe testEmail
    }

    test("getAllUsers should return empty list when no users exist") {
        every { userRepository.getAll() } returns emptyList()

        val result = userQueryService.getAllUsers()

        result shouldHaveSize 0
    }

    test("getAllFullUsers should return list of full users") {
        every { userRepository.getAllFull() } returns listOf(testFullUserEntity)

        val result = userQueryService.getAllFullUsers()

        result shouldHaveSize 1
        result[0].id shouldBe testUserId
        result[0].roles shouldHaveSize 1
        result[0].profile.shouldNotBeNull()
    }

    test("getUser should return user when found") {
        every { userRepository.findById(testUserId) } returns testUserEntity

        val result = userQueryService.getUser(testUserId)

        result.id shouldBe testUserId
        result.email shouldBe testEmail
    }

    test("getUser should throw UserNotFound when user doesn't exist") {
        every { userRepository.findById(testUserId) } returns null

        val exception = shouldThrow<KodexThrowable.UserNotFound> {
            userQueryService.getUser(testUserId)
        }
        exception.message shouldBe "User with id $testUserId not found"
    }

    test("getUserOrNull should return user when found") {
        every { userRepository.findById(testUserId) } returns testUserEntity

        val result = userQueryService.getUserOrNull(testUserId)

        result.shouldNotBeNull()
        result.id shouldBe testUserId
    }

    test("getUserOrNull should return null when user doesn't exist") {
        every { userRepository.findById(testUserId) } returns null

        val result = userQueryService.getUserOrNull(testUserId)

        result.shouldBeNull()
    }

    test("getUserByEmail should return user when found") {
        every { userRepository.findByEmail(testEmail) } returns testUserEntity

        val result = userQueryService.getUserByEmail(testEmail)

        result.id shouldBe testUserId
        result.email shouldBe testEmail
    }

    test("getUserByEmail should throw UserNotFound when user doesn't exist") {
        every { userRepository.findByEmail(testEmail) } returns null

        val exception = shouldThrow<KodexThrowable.UserNotFound> {
            userQueryService.getUserByEmail(testEmail)
        }
        exception.message shouldBe "User with email $testEmail not found"
    }

    test("getUserByPhone should return user when found") {
        every { userRepository.findByPhone(testPhone) } returns testUserEntity

        val result = userQueryService.getUserByPhone(testPhone)

        result.id shouldBe testUserId
        result.phoneNumber shouldBe testPhone
    }

    test("getUserByPhone should throw UserNotFound when user doesn't exist") {
        every { userRepository.findByPhone(testPhone) } returns null

        val exception = shouldThrow<KodexThrowable.UserNotFound> {
            userQueryService.getUserByPhone(testPhone)
        }
        exception.message shouldBe "User with phone number $testPhone not found"
    }

    test("getFullUser should return full user when found") {
        every { userRepository.findFullById(testUserId) } returns testFullUserEntity

        val result = userQueryService.getFullUser(testUserId)

        result.id shouldBe testUserId
        result.roles shouldHaveSize 1
        result.profile.shouldNotBeNull()
        result.customAttributes.shouldNotBeNull()
    }

    test("getFullUser should throw UserNotFound when user doesn't exist") {
        every { userRepository.findFullById(testUserId) } returns null

        val exception = shouldThrow<KodexThrowable.UserNotFound> {
            userQueryService.getFullUser(testUserId)
        }
        exception.message shouldBe "User with id $testUserId not found"
    }

    test("getFullUserOrNull should return full user when found") {
        every { userRepository.findFullById(testUserId) } returns testFullUserEntity

        val result = userQueryService.getFullUserOrNull(testUserId)

        result.shouldNotBeNull()
        result.id shouldBe testUserId
    }

    test("getFullUserOrNull should return null when user doesn't exist") {
        every { userRepository.findFullById(testUserId) } returns null

        val result = userQueryService.getFullUserOrNull(testUserId)

        result.shouldBeNull()
    }

    test("getUserProfile should return profile when found") {
        every { userRepository.findProfileByUserId(testUserId) } returns testProfileEntity

        val result = userQueryService.getUserProfile(testUserId)

        result.firstName shouldBe "John"
        result.lastName shouldBe "Doe"
    }

    test("getUserProfile should throw ProfileNotFound when profile doesn't exist") {
        every { userRepository.findProfileByUserId(testUserId) } returns null

        val exception = shouldThrow<KodexThrowable.ProfileNotFound> {
            userQueryService.getUserProfile(testUserId)
        }
        exception.message shouldBe "Profile not found for user with ID: $testUserId"
    }

    test("getUserProfileOrNull should return profile when found") {
        every { userRepository.findProfileByUserId(testUserId) } returns testProfileEntity

        val result = userQueryService.getUserProfileOrNull(testUserId)

        result.shouldNotBeNull()
        result.firstName shouldBe "John"
    }

    test("getUserProfileOrNull should return null when profile doesn't exist") {
        every { userRepository.findProfileByUserId(testUserId) } returns null

        val result = userQueryService.getUserProfileOrNull(testUserId)

        result.shouldBeNull()
    }

    test("getCustomAttributes should return attributes when found") {
        val attributes = mapOf("key1" to "value1", "key2" to "value2")
        every { userRepository.findCustomAttributesByUserId(testUserId) } returns attributes

        val result = userQueryService.getCustomAttributes(testUserId)

        result shouldBe attributes
        result.size shouldBe 2
    }

    test("getCustomAttributes should return empty map when no attributes exist") {
        every { userRepository.findCustomAttributesByUserId(testUserId) } returns emptyMap()

        val result = userQueryService.getCustomAttributes(testUserId)

        result shouldBe emptyMap()
        result.size shouldBe 0
    }
})
