package com.mustafadakhel.kodex.service.auth

import com.mustafadakhel.kodex.event.AuthEvent
import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.service.token.TokenService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.token.TokenPair
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import java.util.UUID

class AuthServiceTest : FunSpec({
    lateinit var userRepository: UserRepository
    lateinit var hashingService: HashingService
    lateinit var tokenService: TokenService
    lateinit var hookExecutor: HookExecutor
    lateinit var eventBus: EventBus
    lateinit var timeZone: TimeZone
    lateinit var realm: Realm
    lateinit var authService: AuthService

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
        isVerified = true,
        lastLoggedIn = null,
        status = UserStatus.ACTIVE
    )

    val testTokenPair = TokenPair(
        access = "access-token",
        refresh = "refresh-token"
    )

    beforeEach {
        userRepository = mockk()
        hashingService = mockk(relaxed = true)
        tokenService = mockk()
        hookExecutor = mockk()
        eventBus = mockk(relaxed = true)
        timeZone = TimeZone.UTC
        realm = mockk()
        every { realm.owner } returns realmOwner

        authService = DefaultAuthService(
            userRepository,
            hashingService,
            tokenService,
            hookExecutor,
            eventBus,
            timeZone,
            realm
        )
    }

    test("tokenByEmail should authenticate successfully and return tokens") {
        val eventSlot = slot<AuthEvent.LoginSuccess>()

        coEvery { hookExecutor.executeBeforeLogin(testEmail) } returns testEmail
        every { userRepository.findByEmail(testEmail) } returns testUserEntity
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(testPassword, testHashedPassword) } returns true
        every { userRepository.updateLastLogin(testUserId, any()) } returns true
        coEvery { tokenService.issue(testUserId) } returns testTokenPair
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        val result = authService.login(testEmail, testPassword)

        result shouldBe testTokenPair
        coVerify(exactly = 1) { hookExecutor.executeBeforeLogin(testEmail) }
        verify(exactly = 1) { userRepository.findByEmail(testEmail) }
        verify(exactly = 1) { hashingService.verify(testPassword, testHashedPassword) }
        verify(exactly = 1) { userRepository.updateLastLogin(testUserId, any()) }
        coVerify(exactly = 1) { tokenService.issue(testUserId) }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            identifier shouldBe testEmail
            method shouldBe "email"
            realmId shouldBe realmOwner
        }
    }

    test("tokenByEmail should throw InvalidCredentials when user doesn't exist (timing attack prevention)") {
        val eventSlot = slot<AuthEvent.LoginFailed>()

        coEvery { hookExecutor.executeBeforeLogin(testEmail) } returns testEmail
        every { userRepository.findByEmail(testEmail) } returns null
        coEvery { hookExecutor.executeAfterLoginFailure(testEmail) } returns Unit
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
            authService.login(testEmail, testPassword)
        }

        verify(exactly = 1) { hashingService.verify(testPassword, any()) }
        coVerify(exactly = 1) { hookExecutor.executeAfterLoginFailure(testEmail) }

        eventSlot.captured.apply {
            identifier shouldBe testEmail
            reason shouldBe "User not found"
            method shouldBe "email"
            userId shouldBe null
            actorType shouldBe "ANONYMOUS"
        }
    }

    test("tokenByEmail should throw InvalidCredentials when password is wrong") {
        val eventSlot = slot<AuthEvent.LoginFailed>()

        coEvery { hookExecutor.executeBeforeLogin(testEmail) } returns testEmail
        every { userRepository.findByEmail(testEmail) } returns testUserEntity
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(testPassword, testHashedPassword) } returns false
        coEvery { hookExecutor.executeAfterLoginFailure(testEmail) } returns Unit
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
            authService.login(testEmail, testPassword)
        }

        coVerify(exactly = 1) { hookExecutor.executeAfterLoginFailure(testEmail) }

        eventSlot.captured.apply {
            identifier shouldBe testEmail
            reason shouldBe "Invalid password"
            method shouldBe "email"
            userId shouldBe testUserId
            actorType shouldBe "USER"
        }
    }

    test("tokenByEmail should throw UnverifiedAccount when user is not verified") {
        val unverifiedUser = testUserEntity.copy(isVerified = false)

        coEvery { hookExecutor.executeBeforeLogin(testEmail) } returns testEmail
        every { userRepository.findByEmail(testEmail) } returns unverifiedUser
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(testPassword, testHashedPassword) } returns true

        shouldThrow<KodexThrowable.Authorization.UnverifiedAccount> {
            authService.login(testEmail, testPassword)
        }

        verify(exactly = 0) { userRepository.updateLastLogin(any(), any()) }
        coVerify(exactly = 0) { tokenService.issue(any()) }
    }

    test("tokenByPhone should authenticate successfully and return tokens") {
        val eventSlot = slot<AuthEvent.LoginSuccess>()

        coEvery { hookExecutor.executeBeforeLogin(testPhone) } returns testPhone
        every { userRepository.findByPhone(testPhone) } returns testUserEntity
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(testPassword, testHashedPassword) } returns true
        every { userRepository.updateLastLogin(testUserId, any()) } returns true
        coEvery { tokenService.issue(testUserId) } returns testTokenPair
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        val result = authService.loginByPhone(testPhone, testPassword)

        result shouldBe testTokenPair

        eventSlot.captured.apply {
            identifier shouldBe testPhone
            method shouldBe "phone"
        }
    }

    test("tokenByPhone should throw InvalidCredentials when user doesn't exist") {
        val dummyHash = "dummy-hash"
        val eventSlot = slot<AuthEvent.LoginFailed>()

        coEvery { hookExecutor.executeBeforeLogin(testPhone) } returns testPhone
        every { userRepository.findByPhone(testPhone) } returns null
        every { hashingService.hash("dummy-password-for-timing-attack-prevention") } returns dummyHash
        every { hashingService.verify(testPassword, dummyHash) } returns false
        coEvery { hookExecutor.executeAfterLoginFailure(testPhone) } returns Unit
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
            authService.loginByPhone(testPhone, testPassword)
        }

        eventSlot.captured.method shouldBe "phone"
    }

    test("changePassword should change password and publish PasswordChanged event") {
        val oldPassword = "oldpassword"
        val newPassword = "newpassword"
        val newHashedPassword = "new-hashed-password"
        val eventSlot = slot<AuthEvent.PasswordChanged>()

        every { userRepository.findById(testUserId) } returns testUserEntity
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(oldPassword, testHashedPassword) } returns true
        every { hashingService.hash(newPassword) } returns newHashedPassword
        every { userRepository.updatePassword(testUserId, newHashedPassword) } returns true
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        authService.changePassword(testUserId, oldPassword, newPassword)

        verify(exactly = 1) { userRepository.findById(testUserId) }
        verify(exactly = 1) { hashingService.verify(oldPassword, testHashedPassword) }
        verify(exactly = 1) { hashingService.hash(newPassword) }
        verify(exactly = 1) { userRepository.updatePassword(testUserId, newHashedPassword) }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            actorId shouldBe testUserId
            realmId shouldBe realmOwner
        }
    }

    test("changePassword should throw UserNotFound when user doesn't exist") {
        every { userRepository.findById(testUserId) } returns null

        val exception = shouldThrow<KodexThrowable.UserNotFound> {
            authService.changePassword(testUserId, "oldpass", "newpass")
        }
        exception.message shouldBe "User with id $testUserId not found"

        verify(exactly = 0) { hashingService.verify(any(), any()) }
        verify(exactly = 0) { userRepository.updatePassword(any(), any()) }
    }

    test("changePassword should throw InvalidCredentials and publish PasswordChangeFailed when old password is wrong") {
        val oldPassword = "wrongpassword"
        val newPassword = "newpassword"
        val eventSlot = slot<AuthEvent.PasswordChangeFailed>()

        every { userRepository.findById(testUserId) } returns testUserEntity
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(oldPassword, testHashedPassword) } returns false
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
            authService.changePassword(testUserId, oldPassword, newPassword)
        }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            actorId shouldBe testUserId
            reason shouldBe "Invalid old password"
        }
    }

    test("changePassword should throw UserNotFound when update fails") {
        val oldPassword = "oldpassword"
        val newPassword = "newpassword"
        val newHashedPassword = "new-hashed-password"

        every { userRepository.findById(testUserId) } returns testUserEntity
        every { userRepository.getHashedPassword(testUserId) } returns testHashedPassword
        every { hashingService.verify(oldPassword, testHashedPassword) } returns true
        every { hashingService.hash(newPassword) } returns newHashedPassword
        every { userRepository.updatePassword(testUserId, newHashedPassword) } returns false

        shouldThrow<KodexThrowable.UserNotFound> {
            authService.changePassword(testUserId, oldPassword, newPassword)
        }
    }

    test("resetPassword should reset password and publish PasswordReset event") {
        val newPassword = "newpassword"
        val newHashedPassword = "new-hashed-password"
        val eventSlot = slot<AuthEvent.PasswordReset>()

        every { userRepository.findById(testUserId) } returns testUserEntity
        every { hashingService.hash(newPassword) } returns newHashedPassword
        every { userRepository.updatePassword(testUserId, newHashedPassword) } returns true
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        authService.resetPassword(testUserId, newPassword)

        verify(exactly = 1) { userRepository.findById(testUserId) }
        verify(exactly = 1) { hashingService.hash(newPassword) }
        verify(exactly = 1) { userRepository.updatePassword(testUserId, newHashedPassword) }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            actorType shouldBe "ADMIN"
            realmId shouldBe realmOwner
        }
    }

    test("resetPassword should throw UserNotFound when user doesn't exist") {
        every { userRepository.findById(testUserId) } returns null

        val exception = shouldThrow<KodexThrowable.UserNotFound> {
            authService.resetPassword(testUserId, "newpassword")
        }
        exception.message shouldBe "User with id $testUserId not found"
    }

    test("resetPassword should throw UserNotFound when update fails") {
        val newPassword = "newpassword"
        val newHashedPassword = "new-hashed-password"

        every { userRepository.findById(testUserId) } returns testUserEntity
        every { hashingService.hash(newPassword) } returns newHashedPassword
        every { userRepository.updatePassword(testUserId, newHashedPassword) } returns false

        shouldThrow<KodexThrowable.UserNotFound> {
            authService.resetPassword(testUserId, newPassword)
        }
    }

    test("resetPassword should not verify old password") {
        val newPassword = "newpassword"
        val newHashedPassword = "new-hashed-password"

        every { userRepository.findById(testUserId) } returns testUserEntity
        every { hashingService.hash(newPassword) } returns newHashedPassword
        every { userRepository.updatePassword(testUserId, newHashedPassword) } returns true
        coEvery { eventBus.publish(any<AuthEvent.PasswordReset>()) } returns Unit

        authService.resetPassword(testUserId, newPassword)

        verify(exactly = 0) { hashingService.verify(any(), any()) }
        verify(exactly = 0) { userRepository.getHashedPassword(any()) }
    }
})
