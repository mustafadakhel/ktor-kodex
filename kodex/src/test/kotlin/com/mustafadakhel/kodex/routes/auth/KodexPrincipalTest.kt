package com.mustafadakhel.kodex.routes.auth

import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.Role
import com.mustafadakhel.kodex.model.TokenType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class KodexPrincipalTest : DescribeSpec({

    val testUserId = UUID.randomUUID()
    val testRealm = Realm("test-realm")
    val testRoles = listOf(Role("admin", "Administrator"), Role("user", "User"))
    val testToken = "test.jwt.token"

    describe("DefaultKodexPrincipal") {
        it("should store all properties") {
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            principal.userId shouldBe testUserId
            principal.type shouldBe TokenType.AccessToken
            principal.realm shouldBe testRealm
            principal.roles shouldBe testRoles
            principal.token shouldBe testToken
        }

        it("should be instance of KodexPrincipal") {
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            principal.shouldBeInstanceOf<KodexPrincipal>()
        }

        it("should support null token") {
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.RefreshToken,
                realm = testRealm,
                roles = testRoles,
                token = null,
                tokenFamily = null
            )

            principal.token.shouldBeNull()
            principal.userId shouldBe testUserId
        }

        it("should support empty roles list") {
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = emptyList(),
                token = testToken,
                tokenFamily = null
            )

            principal.roles shouldBe emptyList()
        }

        it("should support RefreshToken type") {
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.RefreshToken,
                realm = testRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            principal.type shouldBe TokenType.RefreshToken
        }

        it("should store correct realm information") {
            val customRealm = Realm("custom-realm")
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.AccessToken,
                realm = customRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            principal.realm shouldBe customRealm
            principal.realm.name shouldBe "custom-realm"
        }

        it("should store multiple roles correctly") {
            val multipleRoles = listOf(
                Role("admin", "Administrator"),
                Role("user", "User"),
                Role("moderator", "Moderator")
            )
            val principal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = multipleRoles,
                token = testToken,
                tokenFamily = null
            )

            principal.roles.size shouldBe 3
            principal.roles[0].name shouldBe "admin"
            principal.roles[1].name shouldBe "user"
            principal.roles[2].name shouldBe "moderator"
        }

        it("should handle different user IDs") {
            val userId1 = UUID.randomUUID()
            val userId2 = UUID.randomUUID()

            val principal1 = DefaultKodexPrincipal(
                userId = userId1,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            val principal2 = DefaultKodexPrincipal(
                userId = userId2,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            principal1.userId shouldBe userId1
            principal2.userId shouldBe userId2
            (principal1.userId == principal2.userId) shouldBe false
        }

        it("should implement interface properties") {
            val principal: KodexPrincipal = DefaultKodexPrincipal(
                userId = testUserId,
                type = TokenType.AccessToken,
                realm = testRealm,
                roles = testRoles,
                token = testToken,
                tokenFamily = null
            )

            principal.userId shouldBe testUserId
            principal.type shouldBe TokenType.AccessToken
            principal.realm shouldBe testRealm
            principal.roles shouldBe testRoles
            principal.token shouldBe testToken
        }
    }
})
