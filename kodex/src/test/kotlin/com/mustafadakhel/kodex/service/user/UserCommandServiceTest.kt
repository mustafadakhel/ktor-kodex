package com.mustafadakhel.kodex.service.user

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.extension.UserCreateData
import com.mustafadakhel.kodex.model.FullUser
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.User
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.update.ChangeSet
import com.mustafadakhel.kodex.update.FieldChange
import com.mustafadakhel.kodex.update.UpdateCommand
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import com.mustafadakhel.kodex.update.UpdateResult
import com.mustafadakhel.kodex.update.UpdateUserBatch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import java.util.UUID

class UserCommandServiceTest : FunSpec({
    lateinit var userRepository: UserRepository
    lateinit var hashingService: HashingService
    lateinit var hookExecutor: HookExecutor
    lateinit var eventBus: EventBus
    lateinit var updateCommandProcessor: UpdateCommandProcessor
    lateinit var timeZone: TimeZone
    lateinit var realm: Realm
    lateinit var userCommandService: UserService

    val testUserId = UUID.randomUUID()
    val testEmail = "test@example.com"
    val testPhone = "+1234567890"
    val testPassword = "password123"
    val testHashedPassword = "hashed-password"
    val realmOwner = "test-realm"
    val testTime = LocalDateTime.parse("2024-01-01T12:00:00")

    val testUserEntity = UserEntity(
        id = testUserId,
        email = testEmail,
        phoneNumber = testPhone,
        createdAt = testTime,
        updatedAt = testTime,
        lastLoggedIn = null,
        status = UserStatus.ACTIVE
    )

    val testProfile = UserProfile(
        firstName = "John",
        lastName = "Doe"
    )

    beforeEach {
        userRepository = mockk()
        hashingService = mockk()
        hookExecutor = mockk()
        eventBus = mockk(relaxed = true)
        updateCommandProcessor = mockk()
        timeZone = TimeZone.UTC
        realm = mockk()
        every { realm.owner } returns realmOwner

        userCommandService = DefaultUserService(
            userRepository,
            hashingService,
            hookExecutor,
            eventBus,
            updateCommandProcessor,
            timeZone,
            realm
        )
    }

    test("createUser should execute hooks, hash password, create user, and publish Created event") {
        val transformedData = UserCreateData(testEmail, testPhone, null, testProfile)
        val eventSlot = slot<UserEvent.Created>()

        coEvery { hookExecutor.executeBeforeUserCreate(testEmail, testPhone, testPassword, null, testProfile) } returns transformedData
        every { hashingService.hash(testPassword) } returns testHashedPassword
        every { userRepository.create(
            email = testEmail,
            phone = testPhone,
            hashedPassword = testHashedPassword,
            roleNames = listOf(realmOwner),
            currentTime = any(),
            customAttributes = null,
            profile = testProfile
        ) } returns UserRepository.CreateUserResult.Success(testUserEntity)
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        val result = userCommandService.createUser(
            email = testEmail,
            phone = testPhone,
            password = testPassword,
            roleNames = emptyList(),
            customAttributes = null,
            profile = testProfile
        )

        result?.id shouldBe testUserId
        result?.email shouldBe testEmail
        coVerify(exactly = 1) { hookExecutor.executeBeforeUserCreate(testEmail, testPhone, testPassword, null, testProfile) }
        verify(exactly = 1) { hashingService.hash(testPassword) }
        coVerify(exactly = 1) { eventBus.publish(any<UserEvent.Created>()) }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            realmId shouldBe realmOwner
            email shouldBe testEmail
            phone shouldBe testPhone
        }
    }

    test("createUser should include realm owner in roleNames") {
        val additionalRoles = listOf("user", "moderator")
        val transformedData = UserCreateData(testEmail, testPhone, null, null)

        coEvery { hookExecutor.executeBeforeUserCreate(any(), any(), any(), any(), any()) } returns transformedData
        every { hashingService.hash(any()) } returns testHashedPassword
        every { userRepository.create(
            email = any(),
            phone = any(),
            hashedPassword = any(),
            roleNames = listOf(realmOwner, "user", "moderator"),
            currentTime = any(),
            customAttributes = any(),
            profile = any()
        ) } returns UserRepository.CreateUserResult.Success(testUserEntity)
        coEvery { eventBus.publish(any<UserEvent.Created>()) } returns Unit

        userCommandService.createUser(
            email = testEmail,
            phone = testPhone,
            password = testPassword,
            roleNames = additionalRoles
        )

        verify(exactly = 1) {
            userRepository.create(
                email = any(),
                phone = any(),
                hashedPassword = any(),
                roleNames = listOf(realmOwner, "user", "moderator"),
                currentTime = any(),
                customAttributes = any(),
                profile = any()
            )
        }
    }

    test("createUser should throw EmailAlreadyExists when email is taken") {
        val transformedData = UserCreateData(testEmail, testPhone, null, null)

        coEvery { hookExecutor.executeBeforeUserCreate(any(), any(), any(), any(), any()) } returns transformedData
        every { hashingService.hash(any()) } returns testHashedPassword
        every { userRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns
            UserRepository.CreateUserResult.EmailAlreadyExists

        shouldThrow<KodexThrowable.EmailAlreadyExists> {
            userCommandService.createUser(
                email = testEmail,
                phone = testPhone,
                password = testPassword
            )
        }
    }

    test("createUser should throw PhoneAlreadyExists when phone is taken") {
        val transformedData = UserCreateData(testEmail, testPhone, null, null)

        coEvery { hookExecutor.executeBeforeUserCreate(any(), any(), any(), any(), any()) } returns transformedData
        every { hashingService.hash(any()) } returns testHashedPassword
        every { userRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns
            UserRepository.CreateUserResult.PhoneAlreadyExists

        shouldThrow<KodexThrowable.PhoneAlreadyExists> {
            userCommandService.createUser(
                email = testEmail,
                phone = testPhone,
                password = testPassword
            )
        }
    }

    test("createUser should throw RoleNotFound when invalid role is provided") {
        val invalidRole = "invalid-role"
        val transformedData = UserCreateData(testEmail, testPhone, null, null)

        coEvery { hookExecutor.executeBeforeUserCreate(any(), any(), any(), any(), any()) } returns transformedData
        every { hashingService.hash(any()) } returns testHashedPassword
        every { userRepository.create(any(), any(), any(), any(), any(), any(), any()) } returns
            UserRepository.CreateUserResult.InvalidRole(invalidRole)

        val exception = shouldThrow<KodexThrowable.RoleNotFound> {
            userCommandService.createUser(
                email = testEmail,
                phone = testPhone,
                password = testPassword,
                roleNames = listOf(invalidRole)
            )
        }
        exception.message shouldBe "Role not found: $invalidRole"
    }

    test("updateUser should execute command and publish Updated event when changes exist") {
        val command = UpdateUserBatch(testUserId)
        val fullUser = FullUser(
            id = testUserId,
            email = testEmail,
            phoneNumber = testPhone,
            createdAt = testTime,
            updatedAt = testTime,
            lastLoggedIn = null,
            status = UserStatus.ACTIVE,
            roles = listOf(Role("user", "Regular user")),
            profile = null,
            customAttributes = null
        )
        val changeSet = ChangeSet(
            timestamp = CurrentKotlinInstant,
            changedFields = mapOf(
                "email" to FieldChange("email", "old@example.com", testEmail)
            )
        )
        val successResult = UpdateResult.Success(fullUser, changeSet)
        val eventSlot = slot<UserEvent.Updated>()

        coEvery { updateCommandProcessor.execute(command) } returns successResult
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        val result = userCommandService.updateUser(command)

        result shouldBe successResult
        coVerify(exactly = 1) { updateCommandProcessor.execute(command) }
        coVerify(exactly = 1) { eventBus.publish(any<UserEvent.Updated>()) }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            realmId shouldBe realmOwner
            actorId shouldBe testUserId
            changes shouldBe mapOf("email" to testEmail)
        }
    }

    test("updateUser should not publish event when no changes exist") {
        val command = UpdateUserBatch(testUserId)
        val fullUser = FullUser(
            id = testUserId,
            email = testEmail,
            phoneNumber = testPhone,
            createdAt = testTime,
            updatedAt = testTime,
            lastLoggedIn = null,
            status = UserStatus.ACTIVE,
            roles = emptyList(),
            profile = null,
            customAttributes = null
        )
        val changeSet = ChangeSet(
            timestamp = CurrentKotlinInstant,
            changedFields = emptyMap()
        )
        val successResult = UpdateResult.Success(fullUser, changeSet)

        coEvery { updateCommandProcessor.execute(command) } returns successResult

        val result = userCommandService.updateUser(command)

        result shouldBe successResult
        coVerify(exactly = 1) { updateCommandProcessor.execute(command) }
        coVerify(exactly = 0) { eventBus.publish(any<UserEvent.Updated>()) }
    }

    test("updateUser should not publish event on failure") {
        val command = UpdateUserBatch(testUserId)
        val failureResult = UpdateResult.Failure.NotFound(testUserId)

        coEvery { updateCommandProcessor.execute(command) } returns failureResult

        val result = userCommandService.updateUser(command)

        result shouldBe failureResult
        coVerify(exactly = 1) { updateCommandProcessor.execute(command) }
        coVerify(exactly = 0) { eventBus.publish(any<UserEvent>()) }
    }

    test("updateUser should handle null field values in change metadata") {
        val command = UpdateUserBatch(testUserId)
        val fullUser = FullUser(
            id = testUserId,
            email = null,
            phoneNumber = testPhone,
            createdAt = testTime,
            updatedAt = testTime,
            lastLoggedIn = null,
            status = UserStatus.ACTIVE,
            roles = emptyList(),
            profile = null,
            customAttributes = null
        )
        val changeSet = ChangeSet(
            timestamp = CurrentKotlinInstant,
            changedFields = mapOf(
                "email" to FieldChange("email", testEmail, null)
            )
        )
        val successResult = UpdateResult.Success(fullUser, changeSet)
        val eventSlot = slot<UserEvent.Updated>()

        coEvery { updateCommandProcessor.execute(command) } returns successResult
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        userCommandService.updateUser(command)

        eventSlot.captured.changes shouldBe mapOf("email" to "")
    }
})
