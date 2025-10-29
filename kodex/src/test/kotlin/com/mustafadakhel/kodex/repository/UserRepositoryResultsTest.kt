package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.UserStatus
import com.mustafadakhel.kodex.model.database.UserEntity
import com.mustafadakhel.kodex.util.now
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone.Companion.UTC
import java.util.UUID

class UserRepositoryResultsTest : DescribeSpec({

    val now = now(UTC)
    val testUserId = UUID.randomUUID()
    val testUserEntity = UserEntity(
        id = testUserId,
        createdAt = now,
        updatedAt = now,
        isVerified = false,
        phoneNumber = null,
        email = "test@example.com",
        lastLoggedIn = null,
        status = UserStatus.ACTIVE
    )

    describe("CreateUserResult sealed interface") {
        describe("CreateUserResult.Success") {
            it("should store user entity") {
                val result = UserRepository.CreateUserResult.Success(testUserEntity)

                result.user shouldBe testUserEntity
                result.user.id shouldBe testUserId
                result.user.email shouldBe "test@example.com"
            }

            it("should be instance of CreateUserResult") {
                val result = UserRepository.CreateUserResult.Success(testUserEntity)

                result.shouldBeInstanceOf<UserRepository.CreateUserResult>()
            }

            it("should support data class equality") {
                val result1 = UserRepository.CreateUserResult.Success(testUserEntity)
                val result2 = UserRepository.CreateUserResult.Success(testUserEntity)

                (result1 == result2) shouldBe true
            }

            it("should support data class copy") {
                val original = UserRepository.CreateUserResult.Success(testUserEntity)
                val anotherUser = testUserEntity.copy(email = "other@example.com")
                val copy = original.copy(user = anotherUser)

                copy.user.email shouldBe "other@example.com"
            }
        }

        describe("CreateUserResult.EmailAlreadyExists") {
            it("should be a singleton object") {
                val result1 = UserRepository.CreateUserResult.EmailAlreadyExists
                val result2 = UserRepository.CreateUserResult.EmailAlreadyExists

                (result1 === result2) shouldBe true
            }

            it("should be instance of CreateUserResult") {
                val result = UserRepository.CreateUserResult.EmailAlreadyExists

                result.shouldBeInstanceOf<UserRepository.CreateUserResult>()
            }

            it("should be instance of CreateResult.Duplicate") {
                val result = UserRepository.CreateUserResult.EmailAlreadyExists

                result.shouldBeInstanceOf<UserRepository.CreateResult.Duplicate>()
            }
        }

        describe("CreateUserResult.PhoneAlreadyExists") {
            it("should be a singleton object") {
                val result1 = UserRepository.CreateUserResult.PhoneAlreadyExists
                val result2 = UserRepository.CreateUserResult.PhoneAlreadyExists

                (result1 === result2) shouldBe true
            }

            it("should be instance of CreateUserResult") {
                val result = UserRepository.CreateUserResult.PhoneAlreadyExists

                result.shouldBeInstanceOf<UserRepository.CreateUserResult>()
            }

            it("should be instance of CreateResult.Duplicate") {
                val result = UserRepository.CreateUserResult.PhoneAlreadyExists

                result.shouldBeInstanceOf<UserRepository.CreateResult.Duplicate>()
            }
        }

        describe("CreateUserResult.InvalidRole") {
            it("should store role name") {
                val result = UserRepository.CreateUserResult.InvalidRole("admin")

                result.roleName shouldBe "admin"
            }

            it("should be instance of CreateUserResult") {
                val result = UserRepository.CreateUserResult.InvalidRole("admin")

                result.shouldBeInstanceOf<UserRepository.CreateUserResult>()
            }

            it("should be instance of CreateResult.Duplicate") {
                val result = UserRepository.CreateUserResult.InvalidRole("admin")

                result.shouldBeInstanceOf<UserRepository.CreateResult.Duplicate>()
            }

            it("should support data class equality") {
                val result1 = UserRepository.CreateUserResult.InvalidRole("admin")
                val result2 = UserRepository.CreateUserResult.InvalidRole("admin")

                (result1 == result2) shouldBe true
            }

            it("should have different equality for different roles") {
                val result1 = UserRepository.CreateUserResult.InvalidRole("admin")
                val result2 = UserRepository.CreateUserResult.InvalidRole("user")

                (result1 == result2) shouldBe false
            }
        }
    }

    describe("UpdateUserResult sealed interface") {
        describe("UpdateUserResult.Success") {
            it("should be a singleton object") {
                val result1 = UserRepository.UpdateUserResult.Success
                val result2 = UserRepository.UpdateUserResult.Success

                (result1 === result2) shouldBe true
            }

            it("should be instance of UpdateUserResult") {
                val result = UserRepository.UpdateUserResult.Success

                result.shouldBeInstanceOf<UserRepository.UpdateUserResult>()
            }
        }

        describe("UpdateUserResult.NotFound") {
            it("should be a singleton object") {
                val result1 = UserRepository.UpdateUserResult.NotFound
                val result2 = UserRepository.UpdateUserResult.NotFound

                (result1 === result2) shouldBe true
            }

            it("should be instance of UpdateUserResult") {
                val result = UserRepository.UpdateUserResult.NotFound

                result.shouldBeInstanceOf<UserRepository.UpdateUserResult>()
            }

            it("should be instance of UpdateResult.NotFound") {
                val result = UserRepository.UpdateUserResult.NotFound

                result.shouldBeInstanceOf<UserRepository.UpdateResult.NotFound>()
            }
        }

        describe("UpdateUserResult.EmailAlreadyExists") {
            it("should be a singleton object") {
                val result1 = UserRepository.UpdateUserResult.EmailAlreadyExists
                val result2 = UserRepository.UpdateUserResult.EmailAlreadyExists

                (result1 === result2) shouldBe true
            }

            it("should be instance of UpdateUserResult") {
                val result = UserRepository.UpdateUserResult.EmailAlreadyExists

                result.shouldBeInstanceOf<UserRepository.UpdateUserResult>()
            }

            it("should be instance of UpdateResult.Duplicate") {
                val result = UserRepository.UpdateUserResult.EmailAlreadyExists

                result.shouldBeInstanceOf<UserRepository.UpdateResult.Duplicate>()
            }
        }

        describe("UpdateUserResult.PhoneAlreadyExists") {
            it("should be a singleton object") {
                val result1 = UserRepository.UpdateUserResult.PhoneAlreadyExists
                val result2 = UserRepository.UpdateUserResult.PhoneAlreadyExists

                (result1 === result2) shouldBe true
            }

            it("should be instance of UpdateUserResult") {
                val result = UserRepository.UpdateUserResult.PhoneAlreadyExists

                result.shouldBeInstanceOf<UserRepository.UpdateUserResult>()
            }

            it("should be instance of UpdateResult.Duplicate") {
                val result = UserRepository.UpdateUserResult.PhoneAlreadyExists

                result.shouldBeInstanceOf<UserRepository.UpdateResult.Duplicate>()
            }
        }

        describe("UpdateUserResult.InvalidRole") {
            it("should store role name") {
                val result = UserRepository.UpdateUserResult.InvalidRole("admin")

                result.roleName shouldBe "admin"
            }

            it("should be instance of UpdateUserResult") {
                val result = UserRepository.UpdateUserResult.InvalidRole("admin")

                result.shouldBeInstanceOf<UserRepository.UpdateUserResult>()
            }

            it("should be instance of UpdateResult.NotFound") {
                val result = UserRepository.UpdateUserResult.InvalidRole("admin")

                result.shouldBeInstanceOf<UserRepository.UpdateResult.NotFound>()
            }

            it("should support data class equality") {
                val result1 = UserRepository.UpdateUserResult.InvalidRole("admin")
                val result2 = UserRepository.UpdateUserResult.InvalidRole("admin")

                (result1 == result2) shouldBe true
            }

            it("should have different equality for different roles") {
                val result1 = UserRepository.UpdateUserResult.InvalidRole("admin")
                val result2 = UserRepository.UpdateUserResult.InvalidRole("user")

                (result1 == result2) shouldBe false
            }
        }
    }

    describe("UpdateProfileResult sealed interface") {
        describe("UpdateProfileResult.Success") {
            it("should store user entity") {
                val result = UserRepository.UpdateProfileResult.Success(testUserEntity)

                result.user shouldBe testUserEntity
                result.user.id shouldBe testUserId
            }

            it("should be instance of UpdateProfileResult") {
                val result = UserRepository.UpdateProfileResult.Success(testUserEntity)

                result.shouldBeInstanceOf<UserRepository.UpdateProfileResult>()
            }

            it("should support data class equality") {
                val result1 = UserRepository.UpdateProfileResult.Success(testUserEntity)
                val result2 = UserRepository.UpdateProfileResult.Success(testUserEntity)

                (result1 == result2) shouldBe true
            }
        }

        describe("UpdateProfileResult.NotFound") {
            it("should be a singleton object") {
                val result1 = UserRepository.UpdateProfileResult.NotFound
                val result2 = UserRepository.UpdateProfileResult.NotFound

                (result1 === result2) shouldBe true
            }

            it("should be instance of UpdateProfileResult") {
                val result = UserRepository.UpdateProfileResult.NotFound

                result.shouldBeInstanceOf<UserRepository.UpdateProfileResult>()
            }

            it("should be instance of UpdateResult.NotFound") {
                val result = UserRepository.UpdateProfileResult.NotFound

                result.shouldBeInstanceOf<UserRepository.UpdateResult.NotFound>()
            }
        }
    }

    describe("UpdateRolesResult sealed interface") {
        describe("UpdateRolesResult.Success") {
            it("should be a singleton object") {
                val result1 = UserRepository.UpdateRolesResult.Success
                val result2 = UserRepository.UpdateRolesResult.Success

                (result1 === result2) shouldBe true
            }

            it("should be instance of UpdateRolesResult") {
                val result = UserRepository.UpdateRolesResult.Success

                result.shouldBeInstanceOf<UserRepository.UpdateRolesResult>()
            }
        }

        describe("UpdateRolesResult.InvalidRole") {
            it("should store role name") {
                val result = UserRepository.UpdateRolesResult.InvalidRole("superadmin")

                result.roleName shouldBe "superadmin"
            }

            it("should be instance of UpdateRolesResult") {
                val result = UserRepository.UpdateRolesResult.InvalidRole("superadmin")

                result.shouldBeInstanceOf<UserRepository.UpdateRolesResult>()
            }

            it("should be instance of UpdateResult.NotFound") {
                val result = UserRepository.UpdateRolesResult.InvalidRole("superadmin")

                result.shouldBeInstanceOf<UserRepository.UpdateResult.NotFound>()
            }

            it("should support data class equality") {
                val result1 = UserRepository.UpdateRolesResult.InvalidRole("admin")
                val result2 = UserRepository.UpdateRolesResult.InvalidRole("admin")

                (result1 == result2) shouldBe true
            }
        }
    }

    describe("DeleteResult sealed interface") {
        describe("DeleteResult.Success") {
            it("should be a singleton object") {
                val result1 = UserRepository.DeleteResult.Success
                val result2 = UserRepository.DeleteResult.Success

                (result1 === result2) shouldBe true
            }

            it("should be instance of DeleteResult") {
                val result = UserRepository.DeleteResult.Success

                result.shouldBeInstanceOf<UserRepository.DeleteResult>()
            }
        }

        describe("DeleteResult.NotFound") {
            it("should be a singleton object") {
                val result1 = UserRepository.DeleteResult.NotFound
                val result2 = UserRepository.DeleteResult.NotFound

                (result1 === result2) shouldBe true
            }

            it("should be instance of DeleteResult") {
                val result = UserRepository.DeleteResult.NotFound

                result.shouldBeInstanceOf<UserRepository.DeleteResult>()
            }
        }
    }
})
