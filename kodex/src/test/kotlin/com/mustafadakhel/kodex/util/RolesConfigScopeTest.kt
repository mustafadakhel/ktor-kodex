package com.mustafadakhel.kodex.util

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class RolesConfigScopeTest : DescribeSpec({

    val testRealm = Realm("test-realm")

    describe("RolesConfig") {
        it("should initialize with default realm role") {
            val config = RolesConfig(testRealm)

            config.roles.size shouldBe 1
            config.roles[0].name shouldBe "test-realm"
            config.roles[0].description shouldBe "access to test-realm realm"
        }

        it("should add single role with description") {
            val config = RolesConfig(testRealm)

            config.role("admin", "Administrator role")

            config.roles.size shouldBe 2
            config.roles[1].name shouldBe "admin"
            config.roles[1].description shouldBe "Administrator role"
        }

        it("should add single role without description") {
            val config = RolesConfig(testRealm)

            config.role("user", null)

            config.roles.size shouldBe 2
            config.roles[1].name shouldBe "user"
            config.roles[1].description shouldBe null
        }

        it("should add multiple roles via role() method") {
            val config = RolesConfig(testRealm)

            config.role("admin", "Admin role")
            config.role("user", "User role")
            config.role("moderator", "Mod role")

            config.roles.size shouldBe 4
            config.roles[1].name shouldBe "admin"
            config.roles[2].name shouldBe "user"
            config.roles[3].name shouldBe "moderator"
        }

        it("should add roles via roles() method") {
            val config = RolesConfig(testRealm)
            val rolesToAdd = listOf(
                Role("admin", "Admin"),
                Role("user", "User")
            )

            config.roles(rolesToAdd)

            config.roles.size shouldBe 3
            config.roles[1] shouldBe Role("admin", "Admin")
            config.roles[2] shouldBe Role("user", "User")
        }

        it("should add empty list of roles") {
            val config = RolesConfig(testRealm)

            config.roles(emptyList())

            config.roles.size shouldBe 1
        }

        it("should maintain role order") {
            val config = RolesConfig(testRealm)

            config.role("first", null)
            config.role("second", null)
            config.role("third", null)

            config.roles[0].name shouldBe "test-realm"
            config.roles[1].name shouldBe "first"
            config.roles[2].name shouldBe "second"
            config.roles[3].name shouldBe "third"
        }

        it("should combine role() and roles() methods") {
            val config = RolesConfig(testRealm)

            config.role("admin", "Admin")
            config.roles(listOf(Role("user", "User"), Role("guest", "Guest")))
            config.role("moderator", "Mod")

            config.roles.size shouldBe 5
            config.roles[1].name shouldBe "admin"
            config.roles[2].name shouldBe "user"
            config.roles[3].name shouldBe "guest"
            config.roles[4].name shouldBe "moderator"
        }

        it("should allow duplicate role names") {
            val config = RolesConfig(testRealm)

            config.role("admin", "First admin")
            config.role("admin", "Second admin")

            config.roles.size shouldBe 3
            config.roles[1].description shouldBe "First admin"
            config.roles[2].description shouldBe "Second admin"
        }

        it("should preserve role descriptions") {
            val config = RolesConfig(testRealm)

            config.role("role1", "Description 1")
            config.role("role2", null)
            config.role("role3", "Description 3")

            config.roles[1].description shouldBe "Description 1"
            config.roles[2].description shouldBe null
            config.roles[3].description shouldBe "Description 3"
        }

        it("should work with different realm") {
            val customRealm = Realm("custom-app")
            val config = RolesConfig(customRealm)

            config.roles[0].name shouldBe "custom-app"
            config.roles[0].description shouldBe "access to custom-app realm"
        }

        it("should handle roles with special characters") {
            val config = RolesConfig(testRealm)

            config.role("admin-super", "Super Administrator")
            config.role("user_read", "Read-only user")

            config.roles.size shouldBe 3
            config.roles[1].name shouldBe "admin-super"
            config.roles[2].name shouldBe "user_read"
        }

        it("should add large list of roles") {
            val config = RolesConfig(testRealm)
            val manyRoles = (1..100).map { Role("role$it", "Role $it") }

            config.roles(manyRoles)

            config.roles.size shouldBe 101
            config.roles.last().name shouldBe "role100"
        }

        it("should implement RolesConfigScope interface") {
            val config: RolesConfigScope = RolesConfig(testRealm)

            config.role("test", "test role")

            // Verify interface methods work
            val internalConfig = config as RolesConfig
            internalConfig.roles.size shouldBe 2
        }
    }
})
