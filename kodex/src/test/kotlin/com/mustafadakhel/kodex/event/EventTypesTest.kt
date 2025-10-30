package com.mustafadakhel.kodex.event

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import com.mustafadakhel.kodex.util.CurrentKotlinInstant
import java.util.UUID

class EventTypesTest : DescribeSpec({

    describe("UserEvent") {
        describe("Created") {
            it("should have correct event type") {
                val event = UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    email = "test@example.com",
                    phone = "+1234567890"
                )

                event.eventType shouldBe "USER_CREATED"
            }

            it("should allow null email and phone") {
                val event = UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    email = null,
                    phone = null
                )

                event.email.shouldBeNull()
                event.phone.shouldBeNull()
            }

            it("should have default actor type SYSTEM") {
                val event = UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    email = "test@example.com",
                    phone = null
                )

                event.actorType shouldBe "SYSTEM"
            }

            it("should allow custom actor type") {
                val event = UserEvent.Created(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    email = "test@example.com",
                    phone = null,
                    actorType = "ADMIN"
                )

                event.actorType shouldBe "ADMIN"
            }
        }

        describe("Updated") {
            it("should have correct event type") {
                val event = UserEvent.Updated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    changes = mapOf("email" to "new@example.com")
                )

                event.eventType shouldBe "USER_UPDATED"
            }

            it("should store changes map") {
                val changes = mapOf(
                    "email" to "new@example.com",
                    "phone" to "+9876543210"
                )
                val event = UserEvent.Updated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    changes = changes
                )

                event.changes shouldBe changes
            }
        }

        describe("ProfileUpdated") {
            it("should have correct event type") {
                val event = UserEvent.ProfileUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    changes = mapOf("firstName" to "John")
                )

                event.eventType shouldBe "USER_PROFILE_UPDATED"
            }
        }

        describe("RolesUpdated") {
            it("should have correct event type") {
                val event = UserEvent.RolesUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    previousRoles = setOf("user"),
                    newRoles = setOf("user", "admin")
                )

                event.eventType shouldBe "USER_ROLES_UPDATED"
            }

            it("should have default actor type ADMIN") {
                val event = UserEvent.RolesUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    previousRoles = setOf(),
                    newRoles = setOf("admin")
                )

                event.actorType shouldBe "ADMIN"
            }

            it("should track role changes") {
                val previousRoles = setOf("user", "viewer")
                val newRoles = setOf("user", "admin", "editor")
                val event = UserEvent.RolesUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    previousRoles = previousRoles,
                    newRoles = newRoles
                )

                event.previousRoles shouldContainExactly previousRoles
                event.newRoles shouldContainExactly newRoles
            }
        }

        describe("CustomAttributesUpdated") {
            it("should have correct event type") {
                val event = UserEvent.CustomAttributesUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    attributeCount = 3,
                    keys = setOf("key1", "key2", "key3")
                )

                event.eventType shouldBe "CUSTOM_ATTRIBUTES_UPDATED"
            }

            it("should track attribute count and keys") {
                val keys = setOf("department", "location", "level")
                val event = UserEvent.CustomAttributesUpdated(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    attributeCount = keys.size,
                    keys = keys
                )

                event.attributeCount shouldBe 3
                event.keys shouldContainExactly keys
            }
        }

        describe("CustomAttributesReplaced") {
            it("should have correct event type") {
                val event = UserEvent.CustomAttributesReplaced(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    attributeCount = 2,
                    keys = setOf("key1", "key2")
                )

                event.eventType shouldBe "CUSTOM_ATTRIBUTES_REPLACED"
            }
        }
    }

    describe("AuthEvent") {
        describe("LoginSuccess") {
            it("should have correct event type") {
                val event = AuthEvent.LoginSuccess(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    identifier = "test@example.com",
                    method = "password"
                )

                event.eventType shouldBe "LOGIN_SUCCESS"
            }

            it("should store login method") {
                val event = AuthEvent.LoginSuccess(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    identifier = "test@example.com",
                    method = "oauth"
                )

                event.method shouldBe "oauth"
            }
        }

        describe("LoginFailed") {
            it("should have correct event type") {
                val event = AuthEvent.LoginFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    identifier = "test@example.com",
                    reason = "Invalid password",
                    method = "password"
                )

                event.eventType shouldBe "LOGIN_FAILED"
            }

            it("should allow null userId") {
                val event = AuthEvent.LoginFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    identifier = "unknown@example.com",
                    reason = "User not found",
                    method = "password",
                    userId = null
                )

                event.userId.shouldBeNull()
            }

            it("should have default actor type ANONYMOUS") {
                val event = AuthEvent.LoginFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    identifier = "test@example.com",
                    reason = "Invalid password",
                    method = "password"
                )

                event.actorType shouldBe "ANONYMOUS"
            }

            it("should store failure reason") {
                val event = AuthEvent.LoginFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    identifier = "test@example.com",
                    reason = "Account locked",
                    method = "password"
                )

                event.reason shouldBe "Account locked"
            }
        }

        describe("PasswordChanged") {
            it("should have correct event type") {
                val event = AuthEvent.PasswordChanged(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID()
                )

                event.eventType shouldBe "PASSWORD_CHANGED"
            }

            it("should track user and actor") {
                val userId = UUID.randomUUID()
                val actorId = UUID.randomUUID()
                val event = AuthEvent.PasswordChanged(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = userId,
                    actorId = actorId
                )

                event.userId shouldBe userId
                event.actorId shouldBe actorId
            }
        }

        describe("PasswordChangeFailed") {
            it("should have correct event type") {
                val event = AuthEvent.PasswordChangeFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    reason = "Current password incorrect"
                )

                event.eventType shouldBe "PASSWORD_CHANGE_FAILED"
            }

            it("should store failure reason") {
                val event = AuthEvent.PasswordChangeFailed(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    actorId = UUID.randomUUID(),
                    reason = "Password too weak"
                )

                event.reason shouldBe "Password too weak"
            }
        }

        describe("PasswordReset") {
            it("should have correct event type") {
                val event = AuthEvent.PasswordReset(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID()
                )

                event.eventType shouldBe "PASSWORD_RESET"
            }

            it("should have default actor type ADMIN") {
                val event = AuthEvent.PasswordReset(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID()
                )

                event.actorType shouldBe "ADMIN"
            }
        }
    }

    describe("SecurityEvent") {
        describe("TokenReplayDetected") {
            it("should have correct event type") {
                val event = SecurityEvent.TokenReplayDetected(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = UUID.randomUUID(),
                    tokenId = UUID.randomUUID(),
                    tokenFamily = UUID.randomUUID(),
                    firstUsedAt = "2024-01-01T12:00:00Z",
                    gracePeriodEnd = "2024-01-01T12:05:00Z"
                )

                event.eventType shouldBe "SECURITY_VIOLATION"
            }

            it("should track token replay details") {
                val userId = UUID.randomUUID()
                val tokenId = UUID.randomUUID()
                val tokenFamily = UUID.randomUUID()
                val firstUsed = "2024-01-01T10:00:00Z"
                val gracePeriod = "2024-01-01T10:05:00Z"

                val event = SecurityEvent.TokenReplayDetected(
                    eventId = UUID.randomUUID(),
                    timestamp = CurrentKotlinInstant,
                    realmId = "test-realm",
                    userId = userId,
                    tokenId = tokenId,
                    tokenFamily = tokenFamily,
                    firstUsedAt = firstUsed,
                    gracePeriodEnd = gracePeriod
                )

                event.userId shouldBe userId
                event.tokenId shouldBe tokenId
                event.tokenFamily shouldBe tokenFamily
                event.firstUsedAt shouldBe firstUsed
                event.gracePeriodEnd shouldBe gracePeriod
            }
        }
    }
})
