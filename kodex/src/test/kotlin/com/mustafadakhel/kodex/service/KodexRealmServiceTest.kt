package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.token.TokenManager
import com.mustafadakhel.kodex.token.TokenPair
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import java.util.*

class KodexRealmServiceTest : StringSpec({

    val userRepository = mockk<UserRepository>()
    val tokenManager = mockk<TokenManager>()
    val hashService = mockk<HashingService>()
    val timeZone = TimeZone.UTC
    val realm = Realm("owner")
    val service = KodexRealmService(
        userRepository = userRepository,
        tokenManager = tokenManager,
        hashingService = hashService,
        timeZone = timeZone,
        realm = realm
    )
    val now = LocalDateTime(2024, 1, 1, 0, 0)
    afterTest { clearMocks(userRepository, tokenManager, hashService) }

    "tokenByEmail succeeds when verified and correct password" {
        val userId = UUID.randomUUID()
        val plain = "pwd"
        val hashed = "h_pwd"
        val entity = UserEntity(
            id = userId,
            createdAt = now, updatedAt = now,
            isVerified = true,
            phoneNumber = null,
            email = "u@x",
            lastLoggedIn = null,
            status = UserStatus.ACTIVE
        )
        every { userRepository.findByEmail("u@x") } returns entity
        every { hashService.hash(plain) } returns hashed
        every { userRepository.authenticate(userId, hashed) } returns true
        coEvery { tokenManager.issueNewTokens(userId) } returns TokenPair("access", "refresh")

        runTest {
            service.tokenByEmail("u@x", plain) shouldBe TokenPair("access", "refresh")
        }

        verifyOrder {
            userRepository.findByEmail("u@x")
            hashService.hash(plain)
            userRepository.authenticate(userId, hashed)
        }
        coVerify { tokenManager.issueNewTokens(userId) }
    }

    "tokenByEmail throws InvalidCredentials when user not found" {
        every { userRepository.findByEmail("missing") } returns null

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByEmail("missing", "any")
            }
        }

        verify { userRepository.findByEmail("missing") }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByEmail throws UnverifiedAccount when not verified" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, false, null, "u@x", null, UserStatus.ACTIVE)
        every { userRepository.findByEmail("u@x") } returns entity

        runTest {
            shouldThrow<KodexThrowable.Authorization.UnverifiedAccount> {
                service.tokenByEmail("u@x", "pwd")
            }
        }

        verify { userRepository.findByEmail("u@x") }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByEmail throws InvalidCredentials when password wrong" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, true, null, "u@x", null, UserStatus.ACTIVE)
        every { userRepository.findByEmail("u@x") } returns entity
        every { hashService.hash("bad") } returns "h_bad"
        every { userRepository.authenticate(userId, "h_bad") } returns false

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByEmail("u@x", "bad")
            }
        }

        verifySequence {
            userRepository.findByEmail("u@x")
            hashService.hash("bad")
            userRepository.authenticate(userId, "h_bad")
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByPhone succeeds when verified and correct password" {
        val userId = UUID.randomUUID()
        val plain = "pwd"
        val hashed = "h_pwd"
        val entity = UserEntity(userId, now, now, true, "+100", null, null, UserStatus.ACTIVE)
        every { userRepository.findByPhone("+100") } returns entity
        every { hashService.hash(plain) } returns hashed
        every { userRepository.authenticate(userId, hashed) } returns true
        coEvery { tokenManager.issueNewTokens(userId) } returns TokenPair("a", "r")

        runTest {
            service.tokenByPhone("+100", plain) shouldBe TokenPair("a", "r")
        }

        verifyOrder {
            userRepository.findByPhone("+100")
            hashService.hash(plain)
            userRepository.authenticate(userId, hashed)
        }
        coVerify { tokenManager.issueNewTokens(userId) }
    }

    "tokenByPhone throws InvalidCredentials when phone not found" {
        every { userRepository.findByPhone("x") } returns null

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByPhone("x", "p")
            }
        }

        verify { userRepository.findByPhone("x") }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByPhone throws UnverifiedAccount when not verified" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, false, "+200", null, null, UserStatus.ACTIVE)
        every { userRepository.findByPhone("+200") } returns entity

        runTest {
            shouldThrow<KodexThrowable.Authorization.UnverifiedAccount> {
                service.tokenByPhone("+200", "p")
            }
        }

        verify { userRepository.findByPhone("+200") }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByPhone throws InvalidCredentials when phone password wrong" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, true, "+300", null, null, UserStatus.ACTIVE)
        every { userRepository.findByPhone("+300") } returns entity
        every { hashService.hash("bad") } returns "h_bad"
        every { userRepository.authenticate(userId, "h_bad") } returns false

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByPhone("+300", "bad")
            }
        }

        verifySequence {
            userRepository.findByPhone("+300")
            hashService.hash("bad")
            userRepository.authenticate(userId, "h_bad")
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "createUser returns User on success" {
        val userId = UUID.randomUUID()
        val hashedPwd = "hp"
        val entity = UserEntity(userId, now, now, false, null, null, null, UserStatus.ACTIVE)
        every { hashService.hash("pw") } returns hashedPwd
        every {
            userRepository.create(
                email = null,
                phone = "ph",
                hashedPassword = hashedPwd,
                roleNames = listOf(realm.owner),
                customAttributes = null,
                profile = null,
                currentTime = any()
            )
        } returns UserRepository.CreateUserResult.Success(entity)

        val u = service.createUser(
            email = null,
            phone = "ph",
            password = "pw",
            customAttributes = null,
            profile = null,
        )!!

        u.id shouldBe userId
        verify {
            userRepository.create(
                email = null,
                phone = "ph",
                hashedPassword = hashedPwd,
                roleNames = listOf(realm.owner),
                customAttributes = null,
                profile = null,
                currentTime = any()
            )
        }
    }

    "createUser throws EmailAlreadyExists on duplicate email" {
        every { hashService.hash(any()) } returns "hp"
        every {
            userRepository.create(any(), any(), any(), any(), any(), any(), any())
        } returns UserRepository.CreateUserResult.EmailAlreadyExists

        shouldThrow<KodexThrowable.EmailAlreadyExists> {
            service.createUser("e@x", null, "pw", emptyList(), null, null)
        }
    }

    "createUser throws PhoneAlreadyExists on duplicate phone" {
        every { hashService.hash(any()) } returns "hp"
        every {
            userRepository.create(any(), any(), any(), any(), any(), any(), any())
        } returns UserRepository.CreateUserResult.PhoneAlreadyExists

        shouldThrow<KodexThrowable.PhoneAlreadyExists> {
            service.createUser(null, "p", "pw", emptyList(), null, null)
        }
    }

    "createUser throws RoleNotFound on invalid role" {
        every { hashService.hash(any()) } returns "hp"
        every {
            userRepository.create(any(), any(), any(), any(), any(), any(), any())
        } returns UserRepository.CreateUserResult.InvalidRole("X")

        shouldThrow<KodexThrowable.RoleNotFound> {
            service.createUser(null, "p", "pw", emptyList(), null, null)
        }
    }

    "refresh delegates to tokenManager" {
        val userId = UUID.randomUUID()
        coEvery { tokenManager.refreshTokens(userId, "rt") } returns TokenPair("a", "r")

        runTest {
            service.refresh(userId, "rt") shouldBe TokenPair("a", "r")
        }

        coVerify { tokenManager.refreshTokens(userId, "rt") }
    }

    "revokeTokens delegates to tokenManager" {
        val userId = UUID.randomUUID()
        every { tokenManager.revokeTokensForUser(userId) } just Runs

        service.revokeTokens(userId)

        verify { tokenManager.revokeTokensForUser(userId) }
    }

    "revokeToken delegates to tokenManager" {
        every { tokenManager.revokeToken("tk") } just Runs

        service.revokeToken("tk", delete = true)

        verify { tokenManager.revokeToken("tk") }
    }
})