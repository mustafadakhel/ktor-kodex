package com.mustafadakhel.kodex.service

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.security.AccountLockoutService
import com.mustafadakhel.kodex.security.LockoutResult
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
    val accountLockout = mockk<AccountLockoutService>()
    val timeZone = TimeZone.UTC
    val realm = Realm("owner")
    val service = KodexRealmService(
        userRepository = userRepository,
        tokenManager = tokenManager,
        hashingService = hashService,
        timeZone = timeZone,
        realm = realm,
        accountLockoutService = accountLockout
    )
    val now = LocalDateTime(2024, 1, 1, 0, 0)
    afterTest { clearMocks(userRepository, tokenManager, hashService) }

    "tokenByEmail succeeds when verified and correct password" {
        val userId = UUID.randomUUID()
        val plain = "pwd"
        val hashed = "h_pwd"
        val entity = UserEntity(
            id = userId,
            createdAt = now,
            updatedAt = now,
            isVerified = true,
            phoneNumber = null,
            email = "u@x",
            lastLoggedIn = null,
            status = UserStatus.ACTIVE,
        )
        every { accountLockout.checkLockout("u@x", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.clearFailedAttempts("u@x") } just Runs
        every { userRepository.findByEmail("u@x") } returns entity
        every { hashService.verify(plain, hashed) } returns true
        every { userRepository.getHashedPassword(userId) } returns hashed
        coEvery { tokenManager.issueNewTokens(userId) } returns TokenPair("access", "refresh")

        runTest {
            service.tokenByEmail("u@x", plain) shouldBe TokenPair("access", "refresh")
        }

        verifyOrder {
            accountLockout.checkLockout("u@x", timeZone)
            userRepository.findByEmail("u@x")
            userRepository.getHashedPassword(userId)
            hashService.verify(plain, hashed)
            accountLockout.clearFailedAttempts("u@x")
        }
        coVerify { tokenManager.issueNewTokens(userId) }
    }

    "tokenByEmail throws InvalidCredentials when user not found" {
        every { accountLockout.checkLockout("missing", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.recordFailedAttempt("missing", "unknown", null, "User not found") } just Runs
        every { userRepository.findByEmail("missing") } returns null

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByEmail("missing", "any")
            }
        }

        verifyOrder {
            accountLockout.checkLockout("missing", timeZone)
            userRepository.findByEmail("missing")
            accountLockout.recordFailedAttempt("missing", "unknown", null, "User not found")
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByEmail throws UnverifiedAccount when not verified" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, false, null, "u@x", null, UserStatus.ACTIVE)
        every { accountLockout.checkLockout("u@x", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.clearFailedAttempts("u@x") } just Runs
        every { userRepository.findByEmail("u@x") } returns entity
        every { hashService.verify(any(), any()) } returns true
        every { userRepository.getHashedPassword(userId) } returns "h_pwd"

        runTest {
            shouldThrow<KodexThrowable.Authorization.UnverifiedAccount> {
                service.tokenByEmail("u@x", "pwd")
            }
        }

        verify {
            accountLockout.checkLockout("u@x", timeZone)
            userRepository.findByEmail("u@x")
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByEmail throws InvalidCredentials when password wrong" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, true, null, "u@x", null, UserStatus.ACTIVE)
        every { accountLockout.checkLockout("u@x", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.recordFailedAttempt("u@x", "unknown", null, "Invalid password") } just Runs
        every { userRepository.findByEmail("u@x") } returns entity
        every { hashService.verify("bad", "wrong_hash") } returns false
        every { userRepository.getHashedPassword(userId) } returns "wrong_hash"

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByEmail("u@x", "bad")
            }
        }

        verifyOrder {
            accountLockout.checkLockout("u@x", timeZone)
            userRepository.findByEmail("u@x")
            userRepository.getHashedPassword(userId)
            hashService.verify("bad", "wrong_hash")
            accountLockout.recordFailedAttempt("u@x", "unknown", null, "Invalid password")
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByPhone succeeds when verified and correct password" {
        val userId = UUID.randomUUID()
        val plain = "pwd"
        val hashed = "h_pwd"
        val entity = UserEntity(userId, now, now, true, "+100", null, null, UserStatus.ACTIVE)
        every { accountLockout.checkLockout("+100", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.clearFailedAttempts("+100") } just Runs
        every { userRepository.findByPhone("+100") } returns entity
        every { hashService.verify(plain, hashed) } returns true
        every { userRepository.getHashedPassword(userId) } returns hashed
        coEvery { tokenManager.issueNewTokens(userId) } returns TokenPair("a", "r")

        runTest {
            service.tokenByPhone("+100", plain) shouldBe TokenPair("a", "r")
        }

        verifyOrder {
            accountLockout.checkLockout("+100", timeZone)
            userRepository.findByPhone("+100")
            userRepository.getHashedPassword(userId)
            hashService.verify(plain, hashed)
            accountLockout.clearFailedAttempts("+100")
        }
        coVerify { tokenManager.issueNewTokens(userId) }
    }

    "tokenByPhone throws InvalidCredentials when phone not found" {
        every { accountLockout.checkLockout("x", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.recordFailedAttempt("x", "unknown", null, "User not found") } just Runs
        every { userRepository.findByPhone("x") } returns null

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByPhone("x", "p")
            }
        }

        verifyOrder {
            accountLockout.checkLockout("x", timeZone)
            userRepository.findByPhone("x")
            accountLockout.recordFailedAttempt("x", "unknown", null, "User not found")
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByPhone throws UnverifiedAccount when not verified" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, false, "+200", null, null, UserStatus.ACTIVE)
        every { accountLockout.checkLockout("+200", timeZone) } returns LockoutResult.NotLocked
        every { userRepository.findByPhone("+200") } returns entity
        every { hashService.verify(any(), any()) } returns true
        every { userRepository.getHashedPassword(userId) } returns "h_p"

        runTest {
            shouldThrow<KodexThrowable.Authorization.UnverifiedAccount> {
                service.tokenByPhone("+200", "p")
            }
        }

        verifyOrder {
            accountLockout.checkLockout("+200", timeZone)
            userRepository.findByPhone("+200")
            userRepository.getHashedPassword(userId)
            hashService.verify(any(), any())
        }
        coVerify(exactly = 0) { tokenManager.issueNewTokens(any()) }
    }

    "tokenByPhone throws InvalidCredentials when password wrong" {
        val userId = UUID.randomUUID()
        val entity = UserEntity(userId, now, now, true, "+300", null, null, UserStatus.ACTIVE)
        every { accountLockout.checkLockout("+300", timeZone) } returns LockoutResult.NotLocked
        every { accountLockout.recordFailedAttempt("+300", "unknown", null, "Invalid password") } just Runs
        every { userRepository.findByPhone("+300") } returns entity
        every { hashService.verify("bad", "wrong_hash") } returns false
        every { userRepository.getHashedPassword(userId) } returns "wrong_hash"

        runTest {
            shouldThrow<KodexThrowable.Authorization.InvalidCredentials> {
                service.tokenByPhone("+300", "bad")
            }
        }

        verifyOrder {
            accountLockout.checkLockout("+300", timeZone)
            userRepository.findByPhone("+300")
            userRepository.getHashedPassword(userId)
            hashService.verify("bad", "wrong_hash")
            accountLockout.recordFailedAttempt("+300", "unknown", null, "Invalid password")
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