package com.mustafadakhel.kodex.service.role

import com.mustafadakhel.kodex.event.EventBus
import com.mustafadakhel.kodex.event.UserEvent
import com.mustafadakhel.kodex.extension.HookExecutor
import com.mustafadakhel.kodex.model.Realm
import com.mustafadakhel.kodex.model.database.RoleEntity
import com.mustafadakhel.kodex.repository.UserRepository
import com.mustafadakhel.kodex.service.HashingService
import com.mustafadakhel.kodex.service.user.DefaultUserService
import com.mustafadakhel.kodex.service.user.UserService
import com.mustafadakhel.kodex.throwable.KodexThrowable
import com.mustafadakhel.kodex.update.UpdateCommandProcessor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.TimeZone
import java.util.UUID

class RoleServiceTest : FunSpec({
    lateinit var userRepository: UserRepository
    lateinit var hashingService: HashingService
    lateinit var hookExecutor: HookExecutor
    lateinit var eventBus: EventBus
    lateinit var updateCommandProcessor: UpdateCommandProcessor
    lateinit var timeZone: TimeZone
    lateinit var realm: Realm
    lateinit var roleService: UserService

    val testUserId = UUID.randomUUID()
    val realmOwner = "test-realm"

    beforeEach {
        userRepository = mockk()
        hashingService = mockk(relaxed = true)
        hookExecutor = mockk(relaxed = true)
        eventBus = mockk(relaxed = true)
        updateCommandProcessor = mockk(relaxed = true)
        timeZone = TimeZone.UTC
        realm = mockk()
        every { realm.owner } returns realmOwner

        roleService = DefaultUserService(
            userRepository,
            hashingService,
            hookExecutor,
            eventBus,
            updateCommandProcessor,
            timeZone,
            realm
        )
    }

    test("getSeededRoles should return list of seeded roles") {
        val roleEntities = listOf(
            RoleEntity("admin", "Administrator"),
            RoleEntity("user", "Regular user"),
            RoleEntity("moderator", "Moderator")
        )
        every { userRepository.getAllRoles() } returns roleEntities

        val result = roleService.getSeededRoles()

        result shouldBe listOf("admin", "user", "moderator")
        result shouldHaveSize 3
        result shouldContain "admin"
        verify(exactly = 1) { userRepository.getAllRoles() }
    }

    test("getSeededRoles should return empty list when no seeded roles exist") {
        every { userRepository.getAllRoles() } returns emptyList()

        val result = roleService.getSeededRoles()

        result shouldBe emptyList()
        result shouldHaveSize 0
    }

    test("updateUserRoles should update roles and publish RolesUpdated event on success") {
        val currentRoles = listOf(RoleEntity("user", "Regular user"))
        val newRoleNames = listOf("admin", "moderator")
        val eventSlot = slot<UserEvent.RolesUpdated>()

        every { userRepository.findById(testUserId) } returns mockk()
        every { userRepository.findRoles(testUserId) } returns currentRoles
        every { userRepository.updateRolesForUser(testUserId, newRoleNames) } returns
            UserRepository.UpdateRolesResult.Success
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        roleService.updateUserRoles(testUserId, newRoleNames)

        verify(exactly = 1) { userRepository.findRoles(testUserId) }
        verify(exactly = 1) { userRepository.updateRolesForUser(testUserId, newRoleNames) }
        coVerify(exactly = 1) { eventBus.publish(any<UserEvent.RolesUpdated>()) }

        eventSlot.captured.apply {
            userId shouldBe testUserId
            realmId shouldBe realmOwner
            previousRoles shouldBe setOf("user")
            newRoles shouldBe setOf("admin", "moderator")
            actorType shouldBe "ADMIN"
        }
    }

    test("updateUserRoles should throw RoleNotFound when role doesn't exist") {
        val invalidRoleName = "invalid-role"
        val newRoleNames = listOf("admin", invalidRoleName)

        every { userRepository.findById(testUserId) } returns mockk()
        every { userRepository.findRoles(testUserId) } returns emptyList()
        every { userRepository.updateRolesForUser(testUserId, newRoleNames) } returns
            UserRepository.UpdateRolesResult.InvalidRole(invalidRoleName)

        val exception = shouldThrow<KodexThrowable.RoleNotFound> {
            roleService.updateUserRoles(testUserId, newRoleNames)
        }
        exception.message shouldBe "Role not found: $invalidRoleName"

        coVerify(exactly = 0) { eventBus.publish(any<UserEvent.RolesUpdated>()) }
    }

    test("updateUserRoles should handle empty current roles") {
        val newRoleNames = listOf("user")
        val eventSlot = slot<UserEvent.RolesUpdated>()

        every { userRepository.findById(testUserId) } returns mockk()
        every { userRepository.findRoles(testUserId) } returns emptyList()
        every { userRepository.updateRolesForUser(testUserId, newRoleNames) } returns
            UserRepository.UpdateRolesResult.Success
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        roleService.updateUserRoles(testUserId, newRoleNames)

        eventSlot.captured.apply {
            previousRoles shouldBe emptySet()
            newRoles shouldBe setOf("user")
        }
    }

    test("updateUserRoles should handle empty new roles") {
        val currentRoles = listOf(RoleEntity("admin", "Admin user"))
        val newRoleNames = emptyList<String>()
        val eventSlot = slot<UserEvent.RolesUpdated>()

        every { userRepository.findById(testUserId) } returns mockk()
        every { userRepository.findRoles(testUserId) } returns currentRoles
        every { userRepository.updateRolesForUser(testUserId, newRoleNames) } returns
            UserRepository.UpdateRolesResult.Success
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        roleService.updateUserRoles(testUserId, newRoleNames)

        eventSlot.captured.apply {
            previousRoles shouldBe setOf("admin")
            newRoles shouldBe emptySet()
        }
    }

    test("updateUserRoles should handle multiple roles with same name") {
        val currentRoles = listOf(
            RoleEntity("user", "Regular user"),
            RoleEntity("moderator", "Moderator")
        )
        val newRoleNames = listOf("admin", "admin", "user")
        val eventSlot = slot<UserEvent.RolesUpdated>()

        every { userRepository.findById(testUserId) } returns mockk()
        every { userRepository.findRoles(testUserId) } returns currentRoles
        every { userRepository.updateRolesForUser(testUserId, newRoleNames) } returns
            UserRepository.UpdateRolesResult.Success
        coEvery { eventBus.publish(capture(eventSlot)) } returns Unit

        roleService.updateUserRoles(testUserId, newRoleNames)

        eventSlot.captured.apply {
            newRoles shouldBe setOf("admin", "user")
        }
    }
})
