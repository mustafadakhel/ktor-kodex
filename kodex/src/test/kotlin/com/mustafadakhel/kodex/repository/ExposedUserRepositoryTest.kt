package com.mustafadakhel.kodex.repository

import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.UserProfile
import com.mustafadakhel.kodex.model.database.*
import com.mustafadakhel.kodex.repository.UserRepository.*
import com.mustafadakhel.kodex.repository.database.databaseUserRepository
import com.mustafadakhel.kodex.util.Db
import com.mustafadakhel.kodex.util.exposedTransaction
import com.mustafadakhel.kodex.util.setupExposedEngine
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.deleteAll
import java.util.*

class ExposedUserRepositoryTest : FunSpec({

    lateinit var userRepository: UserRepository
    val now = LocalDateTime(2024, 1, 15, 10, 30)

    beforeEach {
        // H2 + Exposed setup
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
            maximumPoolSize = 5
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        setupExposedEngine(HikariDataSource(config), log = true)
        userRepository = databaseUserRepository()
    }

    afterEach {
        exposedTransaction {
            UserRoles.deleteAll()
            UserCustomAttributes.deleteAll()
            UserProfiles.deleteAll()
            Users.deleteAll()
            Roles.deleteAll()
        }
        Db.clearEngine()
    }

    context("User Creation") {
        test("should create user by email successfully") {
            val email = "test@example.com"
            val pw = "hashedPwd"
            val roles = listOf("USER")
            val profile = UserProfile("John", "Doe", "123 Main St", "pic.jpg")
            val attrs = mapOf("dept" to "Engineering", "level" to "Senior")

            userRepository.seedRoles(listOf(Role("USER", "Standard user")))

            val result = userRepository.create(
                email = email,
                phone = null,
                hashedPassword = pw,
                roleNames = roles,
                customAttributes = attrs,
                profile = profile,
                currentTime = now
            )

            result.shouldBeInstanceOf<CreateUserResult.Success>()
            val user = result.user

            user.email shouldBe email
            user.phoneNumber.shouldBeNull()
            user.isVerified shouldBe false
            user.createdAt shouldBe now
            user.updatedAt shouldBe now

            // verify profile & attrs persisted
            val foundProfile = userRepository.findProfileByUserId(user.id)!!
            foundProfile.firstName shouldBe "John"
            userRepository.findCustomAttributesByUserId(user.id) shouldContainExactly attrs
        }

        test("should create user by phone successfully") {
            val phone = "+1234567890"
            val pw = "hashedPwd"
            val roles = listOf("USER")

            userRepository.seedRoles(listOf(Role("USER", "Standard user")))

            val result = userRepository.create(
                email = null,
                phone = phone,
                hashedPassword = pw,
                roleNames = roles,
                customAttributes = null,
                profile = null,
                currentTime = now
            )

            result.shouldBeInstanceOf<CreateUserResult.Success>()
            val user = result.user

            user.phoneNumber shouldBe phone
            user.email.shouldBeNull()
            user.isVerified shouldBe false
            user.createdAt shouldBe now
            user.updatedAt shouldBe now
        }

        test("should return EmailAlreadyExists when email reused") {
            val email = "dup@example.com"
            val pw = "pwd"
            val roles = listOf("USER")

            userRepository.seedRoles(listOf(Role("USER", "")))
            userRepository.create(email, null, pw, roles, null, null, now)

            userRepository.create(
                email = email,
                phone = null,
                hashedPassword = "other",
                roleNames = roles,
                customAttributes = null,
                profile = null,
                currentTime = now
            ) shouldBe CreateUserResult.EmailAlreadyExists
        }

        test("should return PhoneAlreadyExists when phone reused") {
            val phone = "+1987654321"
            val pw = "pwd"
            val roles = listOf("USER")

            userRepository.seedRoles(listOf(Role("USER", "")))
            userRepository.create(null, phone, pw, roles, null, null, now)

            userRepository.create(
                email = null,
                phone = phone,
                hashedPassword = "other",
                roleNames = roles,
                customAttributes = null,
                profile = null,
                currentTime = now
            ) shouldBe CreateUserResult.PhoneAlreadyExists
        }

        test("should return InvalidRole when role does not exist") {
            userRepository.seedRoles(listOf(Role("ADMIN", "")))

            userRepository.create(
                email = "x@x",
                phone = null,
                hashedPassword = "pw",
                roleNames = listOf("NOPE"),
                customAttributes = null,
                profile = null,
                currentTime = now
            ) shouldBe CreateUserResult.InvalidRole("NOPE")
        }
    }

    // Existence check methods removed - use findByEmail/findByPhone instead
    // context("Existence Checks") {
    //     test("emailExists & phoneExists behave correctly") {
    //         userRepository.seedRoles(listOf(Role("U", "")))
    //         userRepository.create("e1@x", null, "pw", listOf("U"), null, null, now)
    //         userRepository.create(null, "555", "pw", listOf("U"), null, null, now)
    //
    //         userRepository.emailExists("e1@x") shouldBe true
    //         userRepository.emailExists("absent@x") shouldBe false
    //
    //         userRepository.phoneExists("555") shouldBe true
    //         userRepository.phoneExists("000") shouldBe false
    //     }
    // }

    context("User Retrieval") {
        test("findById, findByEmail, findByPhone and getAll") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val r1 = (userRepository.create(
                "a@x",
                null,
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user
            val r2 = (userRepository.create(
                null,
                "999",
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.findById(r1.id)!!.email shouldBe "a@x"
            userRepository.findByEmail("a@x")!!.id shouldBe r1.id
            userRepository.findByPhone("999")!!.id shouldBe r2.id
            userRepository.findById(UUID.randomUUID()).shouldBeNull()

            userRepository.getAll().map { it.id }
                .shouldContainExactlyInAnyOrder(listOf(r1.id, r2.id))
        }
    }

    context("Full User Entity") {
        test("findFullById returns roles, profile, customAttributes") {
            val roles = listOf("R1", "R2")
            userRepository.seedRoles(roles.map { Role(it, "") })
            val profile = UserProfile("F", "L", "Addr", "pic")
            val attrs = mapOf("k" to "v")
            val u =
                (userRepository.create("f@x", null, "pw", roles, attrs, profile, now) as CreateUserResult.Success).user

            val full = userRepository.findFullById(u.id)!!
            full.roles.map(RoleEntity::name)
                .shouldContainExactlyInAnyOrder(roles)
            full.profile!!.address shouldBe "Addr"
            full.customAttributes?.shouldContainExactly(attrs)
        }

        test("findFullById returns null when no user") {
            userRepository.findFullById(UUID.randomUUID()).shouldBeNull()
        }
    }

    context("User Updates") {
        test("updateById modifies email & phone") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val u = (userRepository.create(
                "old@x",
                null,
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.updateById(u.id, "new@x", "+1", null, null, now) shouldBe UpdateUserResult.Success

            val updated = userRepository.findById(u.id)!!
            updated.email shouldBe "new@x"
            updated.phoneNumber shouldBe "+1"
            updated.updatedAt shouldBe now
        }

        test("updateById reports NotFound if user absent") {
            userRepository.updateById(UUID.randomUUID(), "x@x", null, null, null, now) shouldBe UpdateUserResult.NotFound
        }

        test("updateById rejects duplicate email or phone") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val u1 = (userRepository.create(
                "a@x",
                "+123",
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user
            val u2 = (userRepository.create(
                "b@x",
                null,
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.updateById(u2.id, u1.email, null, null, null, now) shouldBe UpdateUserResult.EmailAlreadyExists
            userRepository.updateById(u2.id, null, u1.phoneNumber, null, null, now) shouldBe UpdateUserResult.PhoneAlreadyExists
        }
    }

    context("Roles Management") {
        test("seedRoles & findRoles") {
            userRepository.seedRoles(listOf(Role("A", ""), Role("B", "")))
            val u = (userRepository.create(
                "r@x",
                null,
                "pw",
                listOf("A"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.findRoles(u.id).map(RoleEntity::name) shouldContainExactly listOf("A")
        }

        test("findRoles returns empty if none") {
            userRepository.seedRoles(listOf(Role("A", "")))
            val u = (userRepository.create(
                "e@x",
                null,
                "pw",
                emptyList(),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.findRoles(u.id).shouldBeEmpty()
        }

        test("updateRolesForUser works and rejects invalid") {
            userRepository.seedRoles(listOf(Role("X", ""), Role("Y", "")))
            val u = (userRepository.create(
                "t@x",
                null,
                "pw",
                listOf("X"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.updateRolesForUser(u.id, listOf("Y")) shouldBe UpdateRolesResult.Success
            userRepository.findRoles(u.id).map(RoleEntity::name) shouldContainExactly listOf("Y")

            userRepository.updateRolesForUser(u.id, listOf("Z")) shouldBe UpdateRolesResult.InvalidRole("Z")
        }
    }

    context("User Profile Management") {
        test("findProfileByUserId and updateProfileByUserId") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val orig = UserProfile("Jane", "Doe", "12 Road", "pic.png")
            val u = (userRepository.create(
                "p@x",
                null,
                "pw",
                listOf("U"),
                null,
                orig,
                now
            ) as CreateUserResult.Success).user

            userRepository.findProfileByUserId(u.id)!!.lastName shouldBe "Doe"

            val upd = UserProfile("Janet", "Smith", "34 Ave", "new.png")
            val result = userRepository.updateProfileByUserId(u.id, upd)
            result.shouldBeInstanceOf<UpdateProfileResult.Success>()
            userRepository.findProfileByUserId(u.id)!!.firstName shouldBe "Janet"
        }

        test("updateProfileByUserId returns NotFound if user absent") {
            userRepository.updateProfileByUserId(UUID.randomUUID(), UserProfile("", "", "", "")) shouldBe UpdateProfileResult.NotFound
        }
    }

    context("Custom Attributes Management") {
        test("find, replaceAll and update") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val orig = mapOf("a" to "1")
            val u = (userRepository.create(
                "c@x",
                null,
                "pw",
                listOf("U"),
                orig,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.findCustomAttributesByUserId(u.id) shouldContainExactly orig

            userRepository.replaceAllCustomAttributesByUserId(u.id, mapOf("x" to "y")) shouldBe UpdateUserResult.Success
            userRepository.findCustomAttributesByUserId(u.id) shouldContainExactly mapOf("x" to "y")

            userRepository.updateCustomAttributesByUserId(
                u.id,
                mapOf("x" to "z", "new" to "v")
            ) shouldBe UpdateUserResult.Success
            userRepository.findCustomAttributesByUserId(u.id) shouldContainExactly mapOf("x" to "z", "new" to "v")

            userRepository.replaceAllCustomAttributesByUserId(
                UUID.randomUUID(),
                mapOf()
            ) shouldBe UpdateUserResult.NotFound
            userRepository.updateCustomAttributesByUserId(UUID.randomUUID(), mapOf()) shouldBe UpdateUserResult.NotFound
        }
    }

    context("Authentication") {
        test("authenticate returns pass for existing user") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val u = (userRepository.create(
                "auth@x",
                null,
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user
            userRepository.getHashedPassword(u.id) shouldBe "pw"
        }

        test("getHashedPassword returns null for non‑existent user") {
            userRepository.getHashedPassword(UUID.randomUUID()) shouldBe null
        }
    }

    context("User Verification") {
        test("setVerified toggles and returns true") {
            userRepository.seedRoles(listOf(Role("U", "")))
            val u = (userRepository.create(
                "v@x",
                null,
                "pw",
                listOf("U"),
                null,
                null,
                now
            ) as CreateUserResult.Success).user

            userRepository.setVerified(u.id, true) shouldBe true
            userRepository.findById(u.id)!!.isVerified shouldBe true

            userRepository.setVerified(u.id, false) shouldBe true
            userRepository.findById(u.id)!!.isVerified shouldBe false
        }

        test("setVerified returns false if user absent") {
            userRepository.setVerified(UUID.randomUUID(), true) shouldBe false
        }
    }
})
