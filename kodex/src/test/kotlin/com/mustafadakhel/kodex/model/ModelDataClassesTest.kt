package com.mustafadakhel.kodex.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ModelDataClassesTest : DescribeSpec({

    describe("UserStatus") {
        it("should have ACTIVE status") {
            UserStatus.ACTIVE shouldBe UserStatus.ACTIVE
        }

        it("should have PENDING status") {
            UserStatus.PENDING shouldBe UserStatus.PENDING
        }

        it("should have SUSPENDED status") {
            UserStatus.SUSPENDED shouldBe UserStatus.SUSPENDED
        }

        it("should have three status values") {
            UserStatus.entries.size shouldBe 3
        }

        it("should have distinct status names") {
            val names = UserStatus.entries.map { it.name }.toSet()
            names.size shouldBe 3
        }

        it("should support valueOf") {
            UserStatus.valueOf("ACTIVE") shouldBe UserStatus.ACTIVE
            UserStatus.valueOf("PENDING") shouldBe UserStatus.PENDING
            UserStatus.valueOf("SUSPENDED") shouldBe UserStatus.SUSPENDED
        }
    }

    describe("Role") {
        it("should create role with name and description") {
            val role = Role(name = "admin", description = "Administrator role")

            role.name shouldBe "admin"
            role.description shouldBe "Administrator role"
        }

        it("should create role with null description") {
            val role = Role(name = "user", description = null)

            role.name shouldBe "user"
            role.description.shouldBeNull()
        }

        it("should support data class equality") {
            val role1 = Role("admin", "desc")
            val role2 = Role("admin", "desc")

            (role1 == role2) shouldBe true
        }

        it("should support data class copy") {
            val original = Role("admin", "desc")
            val copy = original.copy(description = "new desc")

            copy.name shouldBe "admin"
            copy.description shouldBe "new desc"
        }

        it("should have different hash codes for different roles") {
            val role1 = Role("admin", "desc1")
            val role2 = Role("user", "desc2")

            (role1.hashCode() == role2.hashCode()) shouldBe false
        }

        it("should support toString") {
            val role = Role("admin", "desc")
            val string = role.toString()

            string shouldBe "Role(name=admin, description=desc)"
        }
    }

    describe("Realm") {
        it("should create realm with owner") {
            val realm = Realm(owner = "my-app")

            realm.owner shouldBe "my-app"
        }

        it("should generate authProviderName from owner") {
            val realm = Realm(owner = "test-app")

            realm.authProviderName shouldBe "auth-jwt-test-app"
        }

        it("should generate name from owner") {
            val realm = Realm(owner = "my-realm")

            realm.name shouldBe "access to my-realm realm"
        }

        it("should handle special characters in owner") {
            val realm = Realm(owner = "app-with-dashes")

            realm.authProviderName shouldBe "auth-jwt-app-with-dashes"
            realm.name shouldBe "access to app-with-dashes realm"
        }

        it("should support data class equality") {
            val realm1 = Realm("app")
            val realm2 = Realm("app")

            (realm1 == realm2) shouldBe true
        }

        it("should support data class copy") {
            val original = Realm("app1")
            val copy = original.copy(owner = "app2")

            copy.owner shouldBe "app2"
            copy.authProviderName shouldBe "auth-jwt-app2"
        }

        it("should have different realms for different owners") {
            val realm1 = Realm("app1")
            val realm2 = Realm("app2")

            (realm1 == realm2) shouldBe false
        }
    }

    describe("UserProfile") {
        it("should create profile with all fields") {
            val profile = UserProfile(
                firstName = "John",
                lastName = "Doe",
                address = "123 Main St",
                profilePicture = "https://example.com/pic.jpg"
            )

            profile.firstName shouldBe "John"
            profile.lastName shouldBe "Doe"
            profile.address shouldBe "123 Main St"
            profile.profilePicture shouldBe "https://example.com/pic.jpg"
        }

        it("should create profile with null fields") {
            val profile = UserProfile(
                firstName = null,
                lastName = null,
                address = null,
                profilePicture = null
            )

            profile.firstName.shouldBeNull()
            profile.lastName.shouldBeNull()
            profile.address.shouldBeNull()
            profile.profilePicture.shouldBeNull()
        }

        it("should use default null values") {
            val profile = UserProfile()

            profile.firstName.shouldBeNull()
            profile.lastName.shouldBeNull()
            profile.address.shouldBeNull()
            profile.profilePicture.shouldBeNull()
        }

        it("should create profile with some fields") {
            val profile = UserProfile(
                firstName = "Jane",
                lastName = "Smith"
            )

            profile.firstName shouldBe "Jane"
            profile.lastName shouldBe "Smith"
            profile.address.shouldBeNull()
            profile.profilePicture.shouldBeNull()
        }

        it("should support data class equality") {
            val profile1 = UserProfile("John", "Doe", null, null)
            val profile2 = UserProfile("John", "Doe", null, null)

            (profile1 == profile2) shouldBe true
        }

        it("should support data class copy") {
            val original = UserProfile("John", "Doe")
            val copy = original.copy(lastName = "Smith")

            copy.firstName shouldBe "John"
            copy.lastName shouldBe "Smith"
        }
    }
})
